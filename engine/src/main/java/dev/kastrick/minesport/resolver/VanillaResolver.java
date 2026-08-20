package dev.kastrick.minesport.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Reads vanilla Minecraft assets directly from the minecraft client jar.
 *
 * Modern clients use assets/minecraft/{blockstates,models,textures}. Minecraft
 * 1.5-1.7 predates the JSON blockstate/model system, so this resolver also
 * synthesizes conservative legacy geometry and understands the historical
 * textures/blocks layout and names. 1.8+ keeps using the real JSON models.
 *
 * When a local vanilla jar exists but is incomplete/modded and a texture cannot
 * be resolved, this resolver lazily fetches Mojang's official client jar for the
 * same version through piston-meta / piston-data and tries the texture again.
 * The official jar is SHA-1 verified and cached locally.
 */
public class VanillaResolver implements AssetResolver {

    private static final String VERSION_MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final int DOWNLOAD_ATTEMPTS = 3;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "((?:1\\.[0-9]+(?:\\.[0-9]+)?)|(?:2[0-9]\\.[0-9]+(?:\\.[0-9]+)?))"
    );
    private static final Pattern LEGACY_SYNTHETIC_MODEL = Pattern.compile(
        "^minecraft:minesport_legacy/([^/]+)/(\\d+)$"
    );

    private static final Map<String, List<String>> LEGACY_TEXTURE_ALIASES = Map.ofEntries(
        Map.entry("gold_ore", List.of("oreGold")),
        Map.entry("iron_ore", List.of("oreIron")),
        Map.entry("coal_ore", List.of("oreCoal")),
        Map.entry("diamond_ore", List.of("oreDiamond")),
        Map.entry("lapis_ore", List.of("oreLapis")),
        Map.entry("redstone_ore", List.of("oreRedstone")),
        Map.entry("emerald_ore", List.of("oreEmerald")),
        Map.entry("nether_quartz_ore", List.of("netherquartz", "quartz_ore")),
        Map.entry("gold_block", List.of("blockGold")),
        Map.entry("iron_block", List.of("blockIron")),
        Map.entry("diamond_block", List.of("blockDiamond")),
        Map.entry("lapis_block", List.of("blockLapis")),
        Map.entry("emerald_block", List.of("blockEmerald")),
        Map.entry("redstone_block", List.of("blockRedstone")),
        Map.entry("bricks", List.of("brick")),
        Map.entry("mossy_cobblestone", List.of("stoneMoss", "cobblestone_mossy")),
        Map.entry("stone_bricks", List.of("stonebricksmooth", "stonebrick")),
        Map.entry("mossy_stone_bricks", List.of("stonebricksmooth_mossy", "stonebrick_mossy")),
        Map.entry("cracked_stone_bricks", List.of("stonebricksmooth_cracked", "stonebrick_cracked")),
        Map.entry("chiseled_stone_bricks", List.of("stonebricksmooth_carved", "stonebrick_carved")),
        Map.entry("glowstone", List.of("lightgem")),
        Map.entry("netherrack", List.of("hellrock")),
        Map.entry("soul_sand", List.of("hellsand")),
        Map.entry("end_stone", List.of("whiteStone")),
        Map.entry("note_block", List.of("musicBlock", "noteblock")),
        Map.entry("spawner", List.of("mobSpawner")),
        Map.entry("cobweb", List.of("web")),
        Map.entry("lily_pad", List.of("waterlily")),
        Map.entry("sugar_cane", List.of("reeds")),
        Map.entry("dead_bush", List.of("deadbush")),
        Map.entry("dandelion", List.of("flower")),
        Map.entry("poppy", List.of("rose", "flower_rose")),
        Map.entry("oak_planks", List.of("wood", "planks_oak")),
        Map.entry("spruce_planks", List.of("wood_spruce", "planks_spruce")),
        Map.entry("birch_planks", List.of("wood_birch", "planks_birch")),
        Map.entry("jungle_planks", List.of("wood_jungle", "planks_jungle")),
        Map.entry("oak_log", List.of("tree_side", "log_oak")),
        Map.entry("spruce_log", List.of("tree_spruce", "log_spruce")),
        Map.entry("birch_log", List.of("tree_birch", "log_birch")),
        Map.entry("jungle_log", List.of("tree_jungle", "log_jungle")),
        Map.entry("oak_log_top", List.of("tree_top", "log_oak_top")),
        Map.entry("spruce_log_top", List.of("tree_top", "log_spruce_top")),
        Map.entry("birch_log_top", List.of("tree_top", "log_birch_top")),
        Map.entry("jungle_log_top", List.of("tree_top", "log_jungle_top")),
        Map.entry("oak_leaves", List.of("leaves")),
        Map.entry("spruce_leaves", List.of("leaves_spruce")),
        Map.entry("birch_leaves", List.of("leaves")),
        Map.entry("jungle_leaves", List.of("leaves_jungle")),
        Map.entry("oak_sapling", List.of("sapling")),
        Map.entry("spruce_sapling", List.of("sapling_spruce")),
        Map.entry("birch_sapling", List.of("sapling_birch")),
        Map.entry("jungle_sapling", List.of("sapling_jungle")),
        Map.entry("mycelium_top", List.of("mycel_top")),
        Map.entry("mycelium_side", List.of("mycel_side")),
        Map.entry("nether_bricks", List.of("netherBrick")),
        Map.entry("nether_wart", List.of("netherStalk_2", "nether_wart_stage_2")),
        Map.entry("redstone_lamp", List.of("redstoneLight", "redstone_lamp_off")),
        Map.entry("redstone_torch", List.of("redtorch_lit", "redstone_torch_on")),
        Map.entry("redstone_wire", List.of("redstoneDust_cross", "redstone_dust_cross")),
        Map.entry("rail", List.of("rail", "rail_normal")),
        Map.entry("powered_rail", List.of("goldenRail", "rail_golden")),
        Map.entry("detector_rail", List.of("detectorRail", "rail_detector")),
        Map.entry("activator_rail", List.of("activatorRail", "rail_activator")),
        Map.entry("crafting_table_top", List.of("workbench_top")),
        Map.entry("crafting_table_side", List.of("workbench_side")),
        Map.entry("crafting_table_front", List.of("workbench_front")),
        Map.entry("furnace_front", List.of("furnace_front", "furnace_front_off")),
        Map.entry("quartz_block", List.of("quartzblock_side", "quartz_block_side")),
        Map.entry("quartz_block_top", List.of("quartzblock_top", "quartz_block_top")),
        Map.entry("chiseled_quartz_block", List.of("quartzblock_chiseled", "quartz_block_chiseled")),
        Map.entry("quartz_pillar", List.of("quartzblock_lines", "quartz_block_lines")),
        Map.entry("quartz_pillar_top", List.of("quartzblock_lines_top", "quartz_block_lines_top")),
        Map.entry("stone_slab", List.of("stoneslab_side", "stone_slab_side")),
        Map.entry("stone_slab_top", List.of("stoneslab_top", "stone_slab_top")),
        Map.entry("glass_pane", List.of("thinglass_top", "glass_pane_top")),
        Map.entry("water", List.of("water", "water_still")),
        Map.entry("lava", List.of("lava", "lava_still"))
    );

    private static final String[] COLORS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    private final File jarFile;
    private final boolean allowPistonFallback;
    private final String minecraftVersion;
    private final boolean legacyModelEra;
    private ZipFile zip;

    private final Map<String, BlockState> stateCache  = new ConcurrentHashMap<>();
    private final Map<String, BlockModel> modelCache  = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> texCache = new ConcurrentHashMap<>();
    private final Set<String> pistonTextureMisses = ConcurrentHashMap.newKeySet();

    private volatile VanillaResolver pistonFallback;
    private volatile boolean pistonFallbackAttempted;

    private static final Set<String> VIRTUAL_PARENTS = Set.of(
        "minecraft:block/block",
        "minecraft:builtin/generated",
        "minecraft:builtin/entity"
    );

    public VanillaResolver(File jarFile) throws IOException {
        this(jarFile, null, true);
    }

    private VanillaResolver(File jarFile, String explicitVersion, boolean allowPistonFallback)
        throws IOException {
        this.jarFile = jarFile;
        this.zip = new ZipFile(jarFile);
        this.allowPistonFallback = allowPistonFallback;
        this.minecraftVersion = explicitVersion == null || explicitVersion.isBlank()
            ? detectMinecraftVersion(jarFile, zip)
            : explicitVersion;
        this.legacyModelEra = isLegacyModelEra(this.minecraftVersion, zip);
    }

    @Override
    public boolean canResolve(String blockId) {
        return blockId.startsWith("minecraft:");
    }

    @Override
    public BlockState resolveBlockState(String blockId) {
        BlockState result = stateCache.get(blockId);
        if (result != null) return result;

        String name = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        String path = "assets/minecraft/blockstates/" + name + ".json";
        try (InputStream in = openEntry(path)) {
            if (in != null) result = ModelParser.parseBlockState(in);
        } catch (Exception e) {
            System.err.println("[VanillaResolver] Failed to parse blockstate for " + blockId + ": " + e.getMessage());
        }

        if (result == null && legacyModelEra && blockId.startsWith("minecraft:") && !isAirId(blockId)) {
            result = synthesizeLegacyBlockState(name);
        }
        if (result != null) stateCache.put(blockId, result);
        return result;
    }

    @Override
    public BlockModel resolveModel(String modelPath) {
        String normalized = normalizeModelPath(modelPath);
        BlockModel cached = modelCache.get(normalized);
        if (cached != null) return cached;

        BlockModel model = null;
        try {
            model = loadModelWithParents(normalized, new HashSet<>());
        } catch (Exception e) {
            System.err.println("[VanillaResolver] Failed to resolve model " + normalized + ": " + e.getMessage());
        }

        if ((model == null || model.isEmpty()) && legacyModelEra) {
            Matcher matcher = LEGACY_SYNTHETIC_MODEL.matcher(normalized);
            if (matcher.matches()) {
                int data = Integer.parseInt(matcher.group(2));
                model = synthesizeLegacyModel(matcher.group(1), data);
            }
        }
        if (model != null) modelCache.put(normalized, model);
        return model;
    }

    @Override
    public BufferedImage resolveTexture(String texturePath) {
        BufferedImage local = resolveTextureLocal(texturePath);
        if (local != null) return local;

        if (!allowPistonFallback || minecraftVersion == null || minecraftVersion.isBlank()) {
            return null;
        }

        VanillaResolver official = getPistonFallback();
        if (official == null) return null;

        BufferedImage recovered = official.resolveTextureLocal(texturePath);
        if (recovered != null) {
            texCache.put(texturePath, recovered);
            System.err.println(
                "[VanillaResolver] Recovered missing texture from official Piston client "
                + minecraftVersion + ": " + texturePath
            );
            return recovered;
        }

        if (pistonTextureMisses.add(texturePath)) {
            System.err.println(
                "[VanillaResolver] Official Piston client also lacks texture: " + texturePath
            );
        }
        return null;
    }

    @Override
    public String name() { return "VanillaResolver(" + jarFile.getName() + ")"; }

    public boolean usesSyntheticLegacyModels() { return legacyModelEra; }

    private BufferedImage resolveTextureLocal(String texturePath) {
        if (texturePath == null || texturePath.isBlank() || texturePath.startsWith("#")) {
            return null;
        }
        BufferedImage cached = texCache.get(texturePath);
        if (cached != null) return cached;

        for (String candidate : textureCandidates(texturePath)) {
            try (InputStream in = openEntry(candidate)) {
                if (in == null) continue;
                BufferedImage image = ImageIO.read(in);
                if (image != null) {
                    texCache.put(texturePath, image);
                    return image;
                }
            } catch (Exception e) {
                System.err.println(
                    "[VanillaResolver] Failed to load texture " + texturePath
                    + " from " + candidate + ": " + e.getMessage()
                );
            }
        }
        return null;
    }

    private List<String> textureCandidates(String texturePath) {
        String normalized = normalizeTexturePath(texturePath);
        int colon = normalized.indexOf(':');
        String namespace = colon >= 0 ? normalized.substring(0, colon) : "minecraft";
        String relative = colon >= 0 ? normalized.substring(colon + 1) : normalized;

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add("assets/" + namespace + "/textures/" + relative + ".png");
        if (!"minecraft".equals(namespace)) return new ArrayList<>(candidates);

        String base = relative;
        if (base.startsWith("block/")) base = base.substring("block/".length());
        else if (base.startsWith("blocks/")) base = base.substring("blocks/".length());
        else return new ArrayList<>(candidates);

        for (String alias : legacyNames(base)) {
            candidates.add("assets/minecraft/textures/blocks/" + alias + ".png");
            candidates.add("textures/blocks/" + alias + ".png");
        }
        return new ArrayList<>(candidates);
    }

    private static List<String> legacyNames(String base) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(base);
        List<String> aliases = LEGACY_TEXTURE_ALIASES.get(base);
        if (aliases != null) names.addAll(aliases);

        if (base.endsWith("_wool")) {
            String color = base.substring(0, base.length() - "_wool".length());
            for (int i = 0; i < COLORS.length; i++) {
                if (COLORS[i].equals(color)) {
                    names.add("cloth_" + i);
                    names.add("wool_colored_" + color.replace("light_gray", "silver"));
                    break;
                }
            }
        }
        return new ArrayList<>(names);
    }

    private VanillaResolver getPistonFallback() {
        VanillaResolver ready = pistonFallback;
        if (ready != null) return ready;
        if (pistonFallbackAttempted) return null;

        synchronized (this) {
            if (pistonFallback != null) return pistonFallback;
            if (pistonFallbackAttempted) return null;
            pistonFallbackAttempted = true;

            System.err.println(
                "[VanillaResolver] Local texture miss — checking official Piston data for Minecraft "
                + minecraftVersion
            );
            File officialJar = findOfficialMinecraftJar(minecraftVersion);
            if (officialJar == null || !officialJar.isFile()) {
                System.err.println(
                    "[VanillaResolver] Official Piston fallback unavailable for Minecraft "
                    + minecraftVersion
                );
                return null;
            }

            try {
                if (jarFile.equals(officialJar)) return null;
                pistonFallback = new VanillaResolver(officialJar, minecraftVersion, false);
                return pistonFallback;
            } catch (IOException e) {
                System.err.println(
                    "[VanillaResolver] Could not open official Piston client jar: " + e.getMessage()
                );
                return null;
            }
        }
    }

    private BlockModel loadModelWithParents(String modelPath) throws IOException {
        return loadModelWithParents(modelPath, new HashSet<>());
    }

    private BlockModel loadModelWithParents(String modelPath, Set<String> visited) throws IOException {
        if (VIRTUAL_PARENTS.contains(modelPath)) return new BlockModel();
        if (!visited.add(modelPath)) return new BlockModel();

        String jarPath = "assets/" + modelPath.replace(":", "/models/") + ".json";
        InputStream in = openEntry(jarPath);
        if (in == null) return new BlockModel();

        BlockModel model;
        try { model = ModelParser.parseBlockModel(in); }
        finally { in.close(); }

        if (model.parentId != null && !model.parentId.isEmpty()) {
            String parentPath = normalizeModelPath(model.parentId);
            if (!VIRTUAL_PARENTS.contains(parentPath) && !visited.contains(parentPath)) {
                BlockModel parent = modelCache.get(parentPath);
                if (parent == null) {
                    parent = loadModelWithParents(parentPath, visited);
                    if (parent != null) modelCache.put(parentPath, parent);
                }
                if (parent != null) {
                    if (model.isEmpty()) model.elements = parent.elements;
                    model.mergeTextures(parent.textures);
                }
            }
        }

        return model;
    }

    private static BlockState synthesizeLegacyBlockState(String blockName) {
        BlockState state = new BlockState();
        state.format = BlockState.Format.VARIANTS;
        for (int data = 0; data < 16; data++) {
            BlockState.ModelApplication application = new BlockState.ModelApplication();
            application.modelPath = "minecraft:minesport_legacy/" + blockName + "/" + data;
            if (isStair(blockName)) application.y = stairRotation(data);
            state.variants.put("legacy_data=" + data, List.of(application));
        }
        return state;
    }

    private static BlockModel synthesizeLegacyModel(String blockName, int data) {
        return switch (blockName) {
            case "grass_block" -> cubeModel(grassFaces());
            case "mycelium" -> cubeModel(
                faceSet("mycelium_side", "mycelium_side", "mycelium_side", "mycelium_side", "mycelium_top", "dirt", -1)
            );
            case "bookshelf" -> cubeModel(
                faceSet("bookshelf", "bookshelf", "bookshelf", "bookshelf", "oak_planks", "oak_planks", -1)
            );
            case "tnt" -> cubeModel(
                faceSet("tnt_side", "tnt_side", "tnt_side", "tnt_side", "tnt_top", "tnt_bottom", -1)
            );
            case "crafting_table" -> cubeModel(
                faceSet("crafting_table_front", "crafting_table_side", "crafting_table_side", "crafting_table_side", "crafting_table_top", "oak_planks", -1)
            );
            case "furnace" -> cubeModel(
                faceSet("furnace_front", "furnace_side", "furnace_side", "furnace_side", "furnace_side", "furnace_side", -1)
            );
            case "pumpkin" -> cubeModel(
                faceSet("pumpkin_face", "pumpkin_side", "pumpkin_side", "pumpkin_side", "pumpkin_top", "pumpkin_top", -1)
            );
            case "jack_o_lantern" -> cubeModel(
                faceSet("pumpkin_jack", "pumpkin_side", "pumpkin_side", "pumpkin_side", "pumpkin_top", "pumpkin_top", -1)
            );
            case "melon" -> cubeModel(
                faceSet("melon_side", "melon_side", "melon_side", "melon_side", "melon_top", "melon_top", -1)
            );
            case "cactus" -> cubeModel(
                faceSet("cactus_side", "cactus_side", "cactus_side", "cactus_side", "cactus_top", "cactus_bottom", -1)
            );
            case "sandstone", "chiseled_sandstone", "cut_sandstone" -> cubeModel(
                faceSet(sandstoneSide(blockName), sandstoneSide(blockName), sandstoneSide(blockName), sandstoneSide(blockName), "sandstone_top", "sandstone_bottom", -1)
            );
            case "quartz_pillar" -> cubeModel(
                faceSet("quartz_pillar", "quartz_pillar", "quartz_pillar", "quartz_pillar", "quartz_pillar_top", "quartz_pillar_top", -1)
            );
            case "oak_log", "spruce_log", "birch_log", "jungle_log" -> logModel(blockName);
            case "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "vine" -> cubeModel(
                faceSet(blockName, blockName, blockName, blockName, blockName, blockName, 0)
            );
            case "glass_pane", "iron_bars" -> crossModel(blockName.equals("glass_pane") ? "glass" : "iron_bars", 2f / 16f);
            case "dandelion", "poppy", "brown_mushroom", "red_mushroom", "dead_bush", "short_grass", "fern",
                 "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "sugar_cane" -> crossModel(blockName, 1f / 16f);
            case "torch", "redstone_torch" -> postModel(blockName, 6f / 16f, 10f / 16f);
            case "rail", "powered_rail", "detector_rail", "activator_rail", "redstone_wire" -> flatModel(blockName);
            case "smooth_stone_slab", "sandstone_slab", "oak_slab", "cobblestone_slab", "brick_slab", "stone_brick_slab", "nether_brick_slab", "quartz_slab" -> slabModel(blockName, data);
            default -> {
                if (isStair(blockName)) yield stairModel(blockName, data);
                yield cubeModel(faceSet(blockName, blockName, blockName, blockName, blockName, blockName, tintForLegacy(blockName)));
            }
        };
    }

    private static BlockModel logModel(String blockName) {
        String top = blockName + "_top";
        return cubeModel(faceSet(blockName, blockName, blockName, blockName, top, top, -1));
    }

    private static BlockModel slabModel(String blockName, int data) {
        boolean top = (data & 8) != 0;
        float y0 = top ? 8 : 0;
        float y1 = top ? 16 : 8;
        String texture = slabTexture(blockName);
        BlockModel model = new BlockModel();
        model.elements.add(element(0, y0, 0, 16, y1, 16, faceSet(texture, texture, texture, texture, texture, texture, -1)));
        return model;
    }

    private static BlockModel stairModel(String blockName, int data) {
        boolean upsideDown = (data & 4) != 0;
        float base0 = upsideDown ? 8 : 0;
        float base1 = upsideDown ? 16 : 8;
        float step0 = upsideDown ? 0 : 8;
        float step1 = upsideDown ? 8 : 16;
        String texture = stairTexture(blockName);
        BlockModel model = new BlockModel();
        Map<String, FaceSpec> faces = faceSet(texture, texture, texture, texture, texture, texture, -1);
        model.elements.add(element(0, base0, 0, 16, base1, 16, faces));
        model.elements.add(element(0, step0, 8, 16, step1, 16, faces));
        return model;
    }

    private static BlockModel crossModel(String texture, float thickness) {
        BlockModel model = new BlockModel();
        float half = thickness * 8f;
        model.elements.add(element(8 - half, 0, 0, 8 + half, 16, 16, allFaces(texture, tintForLegacy(texture))));
        model.elements.add(element(0, 0, 8 - half, 16, 16, 8 + half, allFaces(texture, tintForLegacy(texture))));
        return model;
    }

    private static BlockModel postModel(String texture, float width, float height) {
        BlockModel model = new BlockModel();
        float half = width * 8f;
        model.elements.add(element(8 - half, 0, 8 - half, 8 + half, height * 16f, 8 + half, allFaces(texture, -1)));
        return model;
    }

    private static BlockModel flatModel(String texture) {
        BlockModel model = new BlockModel();
        model.elements.add(element(0, 0, 0, 16, 1, 16, allFaces(texture, tintForLegacy(texture))));
        return model;
    }

    private static BlockModel cubeModel(Map<String, FaceSpec> faces) {
        BlockModel model = new BlockModel();
        model.elements.add(element(0, 0, 0, 16, 16, 16, faces));
        return model;
    }

    private record FaceSpec(String texture, int tint) {}

    private static Map<String, FaceSpec> allFaces(String texture, int tint) {
        return faceSet(texture, texture, texture, texture, texture, texture, tint);
    }

    private static Map<String, FaceSpec> grassFaces() {
        Map<String, FaceSpec> faces = new LinkedHashMap<>();
        faces.put("north", new FaceSpec("grass_side", -1));
        faces.put("south", new FaceSpec("grass_side", -1));
        faces.put("east", new FaceSpec("grass_side", -1));
        faces.put("west", new FaceSpec("grass_side", -1));
        faces.put("up", new FaceSpec("grass_top", 0));
        faces.put("down", new FaceSpec("dirt", -1));
        return faces;
    }

    private static Map<String, FaceSpec> faceSet(
        String north, String south, String east, String west, String up, String down, int tint
    ) {
        Map<String, FaceSpec> faces = new LinkedHashMap<>();
        faces.put("north", new FaceSpec(north, tintForFace(north, tint)));
        faces.put("south", new FaceSpec(south, tintForFace(south, tint)));
        faces.put("east", new FaceSpec(east, tintForFace(east, tint)));
        faces.put("west", new FaceSpec(west, tintForFace(west, tint)));
        faces.put("up", new FaceSpec(up, tintForFace(up, tint)));
        faces.put("down", new FaceSpec(down, tintForFace(down, tint)));
        return faces;
    }

    private static int tintForFace(String texture, int requested) {
        if (requested < 0) return -1;
        if (texture.equals("dirt") || texture.contains("log") || texture.contains("planks")) return -1;
        return requested;
    }

    private static BlockModel.Element element(
        float x0, float y0, float z0, float x1, float y1, float z1,
        Map<String, FaceSpec> faces
    ) {
        BlockModel.Element element = new BlockModel.Element();
        element.from = new float[]{x0, y0, z0};
        element.to = new float[]{x1, y1, z1};
        for (var entry : faces.entrySet()) {
            BlockModel.Face face = new BlockModel.Face();
            face.texture = "minecraft:block/" + entry.getValue().texture();
            face.cullface = entry.getKey();
            face.tintindex = entry.getValue().tint();
            element.faces.put(entry.getKey(), face);
        }
        return element;
    }

    private static int tintForLegacy(String blockName) {
        return blockName.contains("leaves") || blockName.equals("vine") || blockName.equals("short_grass")
            || blockName.equals("fern") || blockName.equals("grass_top") ? 0 : -1;
    }

    private static String sandstoneSide(String blockName) {
        return switch (blockName) {
            case "chiseled_sandstone" -> "sandstone_carved";
            case "cut_sandstone" -> "sandstone_smooth";
            default -> "sandstone_side";
        };
    }

    private static String slabTexture(String blockName) {
        return switch (blockName) {
            case "sandstone_slab" -> "sandstone_side";
            case "oak_slab" -> "oak_planks";
            case "cobblestone_slab" -> "cobblestone";
            case "brick_slab" -> "bricks";
            case "stone_brick_slab" -> "stone_bricks";
            case "nether_brick_slab" -> "nether_bricks";
            case "quartz_slab" -> "quartz_block";
            default -> "stone_slab";
        };
    }

    private static String stairTexture(String blockName) {
        if (blockName.startsWith("oak_")) return "oak_planks";
        if (blockName.startsWith("spruce_")) return "spruce_planks";
        if (blockName.startsWith("birch_")) return "birch_planks";
        if (blockName.startsWith("jungle_")) return "jungle_planks";
        if (blockName.startsWith("cobblestone_")) return "cobblestone";
        if (blockName.startsWith("brick_")) return "bricks";
        if (blockName.startsWith("stone_brick_")) return "stone_bricks";
        if (blockName.startsWith("nether_brick_")) return "nether_bricks";
        if (blockName.startsWith("sandstone_")) return "sandstone_side";
        if (blockName.startsWith("quartz_")) return "quartz_block";
        return blockName;
    }

    private static boolean isStair(String blockName) {
        return blockName.endsWith("_stairs");
    }

    private static int stairRotation(int data) {
        return switch (data & 3) {
            case 0 -> 90;
            case 1 -> 270;
            case 2 -> 180;
            default -> 0;
        };
    }

    private static boolean isAirId(String blockId) {
        return blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air") || blockId.equals("minecraft:void_air");
    }

    private InputStream openEntry(String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        return zip.getInputStream(entry);
    }

    private static String normalizeModelPath(String path) {
        if (path.contains(":")) return path;
        return "minecraft:" + path;
    }

    private static String normalizeTexturePath(String path) {
        if (path.startsWith("#")) return path;
        if (path.contains(":")) return path;
        return "minecraft:" + path;
    }

    public void close() {
        try { if (zip != null) zip.close(); }
        catch (IOException ignored) {}

        VanillaResolver fallback = pistonFallback;
        if (fallback != null) fallback.close();
    }

    public static File findMinecraftJar(String version) {
        List<File> candidates = new ArrayList<>();

        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            candidates.add(new File(appdata, ".minecraft/versions/" + version + "/" + version + ".jar"));
            candidates.add(new File(appdata,
                "FreesmLauncher/libraries/com/mojang/minecraft/" + version + "/minecraft-" + version + "-client.jar"));
        }
        candidates.add(new File(System.getProperty("user.home"),
            ".minecraft/versions/" + version + "/" + version + ".jar"));
        candidates.add(new File(System.getProperty("user.home"),
            "Library/Application Support/minecraft/versions/" + version + "/" + version + ".jar"));

        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return findOfficialMinecraftJar(version);
    }

    public static File findOfficialMinecraftJar(String version) {
        if (version == null || version.isBlank()) return null;

        try {
            File cacheDir = new File(
                System.getProperty("java.io.tmpdir"),
                "minesport_jars/official/" + safeVersion(version)
            );
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw new IOException("could not create cache directory " + cacheDir);
            }

            File jarFile = new File(cacheDir, "client.jar");
            File shaFile = new File(cacheDir, "client.sha1");

            if (jarFile.isFile() && shaFile.isFile()) {
                String cachedSha = Files.readString(shaFile.toPath(), StandardCharsets.UTF_8).trim();
                if (isSha1(cachedSha) && sha1(jarFile).equalsIgnoreCase(cachedSha)) {
                    return jarFile;
                }
            }

            JsonObject versionMetadata = fetchVersionMetadata(version);
            if (versionMetadata == null) return null;
            JsonObject downloads = versionMetadata.getAsJsonObject("downloads");
            JsonObject client = downloads == null ? null : downloads.getAsJsonObject("client");
            if (client == null || !client.has("url") || !client.has("sha1")) {
                System.err.println("[VanillaResolver] Version metadata has no client download for " + version);
                return null;
            }

            String url = client.get("url").getAsString();
            String expectedSha = client.get("sha1").getAsString();
            if (!isSha1(expectedSha)) {
                System.err.println("[VanillaResolver] Invalid client SHA-1 in metadata for " + version);
                return null;
            }

            if (jarFile.isFile() && sha1(jarFile).equalsIgnoreCase(expectedSha)) {
                Files.writeString(shaFile.toPath(), expectedSha, StandardCharsets.UTF_8);
                return jarFile;
            }

            System.err.println(
                "[VanillaResolver] Downloading official Minecraft " + version
                + " client from Piston data..."
            );
            downloadVerified(URI.create(url), jarFile, expectedSha);
            Files.writeString(shaFile.toPath(), expectedSha, StandardCharsets.UTF_8);
            System.err.println("[VanillaResolver] Official client cached at " + jarFile.getAbsolutePath());
            return jarFile;

        } catch (Exception e) {
            System.err.println("[VanillaResolver] Piston client lookup failed: " + e.getMessage());
            return null;
        }
    }

    private static JsonObject fetchVersionMetadata(String version) throws IOException {
        JsonObject manifest = JsonParser.parseString(
            readTextWithRetries(URI.create(VERSION_MANIFEST_URL))
        ).getAsJsonObject();
        JsonArray versions = manifest.getAsJsonArray("versions");
        if (versions == null) {
            throw new IOException("Piston version manifest contains no versions array");
        }

        String metadataUrl = null;
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("id") || !entry.has("url")) continue;
            if (version.equals(entry.get("id").getAsString())) {
                metadataUrl = entry.get("url").getAsString();
                break;
            }
        }
        if (metadataUrl == null) {
            System.err.println("[VanillaResolver] Version " + version + " not present in Piston manifest");
            return null;
        }

        return JsonParser.parseString(readTextWithRetries(URI.create(metadataUrl))).getAsJsonObject();
    }

    private static String readTextWithRetries(URI uri) throws IOException {
        Exception last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "Minesport/0.1")
                    .GET()
                    .build();
                HttpResponse<String> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                last = new IOException("HTTP " + response.statusCode() + " from " + uri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading " + uri, e);
            } catch (Exception e) {
                last = e;
            }

            if (attempt < DOWNLOAD_ATTEMPTS) sleepBeforeRetry(attempt);
        }
        throw new IOException(
            "download failed after " + DOWNLOAD_ATTEMPTS + " attempts: " + uri,
            last
        );
    }

    private static void downloadVerified(URI uri, File destination, String expectedSha) throws IOException {
        Exception last = null;
        File part = new File(destination.getParentFile(), destination.getName() + ".part");

        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(part.toPath());
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "Minesport/0.1")
                    .GET()
                    .build();
                HttpResponse<InputStream> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try { response.body().close(); } catch (Exception ignored) {}
                    throw new IOException("HTTP " + response.statusCode() + " from " + uri);
                }

                try (InputStream in = response.body(); OutputStream out = new FileOutputStream(part)) {
                    in.transferTo(out);
                }

                String actualSha = sha1(part);
                if (!actualSha.equalsIgnoreCase(expectedSha)) {
                    throw new IOException(
                        "SHA-1 mismatch: expected " + expectedSha + ", got " + actualSha
                    );
                }

                try {
                    Files.move(
                        part.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (IOException atomicMoveFailed) {
                    Files.move(
                        part.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    );
                }
                return;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading " + uri, e);
            } catch (Exception e) {
                last = e;
                try { Files.deleteIfExists(part.toPath()); } catch (IOException ignored) {}
            }

            if (attempt < DOWNLOAD_ATTEMPTS) sleepBeforeRetry(attempt);
        }

        throw new IOException(
            "download failed after " + DOWNLOAD_ATTEMPTS + " attempts: " + uri,
            last
        );
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during retry backoff", e);
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isSha1(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{40}");
    }

    private static String safeVersion(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean isLegacyModelEra(String version, ZipFile zip) {
        if (zip.getEntry("assets/minecraft/blockstates/stone.json") != null) return false;
        if (version != null) {
            Matcher matcher = Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?$").matcher(version);
            if (matcher.matches()) {
                int minor = Integer.parseInt(matcher.group(1));
                return minor >= 5 && minor <= 7;
            }
        }
        return zip.getEntry("textures/blocks/stone.png") != null;
    }

    private static String detectMinecraftVersion(File jarFile, ZipFile zip) {
        try {
            ZipEntry versionEntry = zip.getEntry("version.json");
            if (versionEntry != null) {
                try (InputStream in = zip.getInputStream(versionEntry)) {
                    JsonObject versionJson = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                    ).getAsJsonObject();
                    if (versionJson.has("id")) {
                        String id = versionJson.get("id").getAsString();
                        if (!id.isBlank()) return id;
                    }
                }
            }
        } catch (Exception ignored) {}

        String filename = jarFile.getName();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        if (filename.startsWith("minecraft-") && filename.endsWith("-client")) {
            filename = filename.substring("minecraft-".length(), filename.length() - "-client".length());
        }
        if (looksLikeVersion(filename)) return filename;

        File parent = jarFile.getParentFile();
        if (parent != null && looksLikeVersion(parent.getName())) return parent.getName();

        Matcher matcher = VERSION_PATTERN.matcher(jarFile.getAbsolutePath());
        String found = null;
        while (matcher.find()) found = matcher.group(1);
        return found;
    }

    private static boolean looksLikeVersion(String value) {
        return value != null && VERSION_PATTERN.matcher(value).matches();
    }
}
