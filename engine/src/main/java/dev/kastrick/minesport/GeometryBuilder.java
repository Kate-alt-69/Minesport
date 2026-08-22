package dev.kastrick.minesport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastrick.minesport.export.BlockGeometryClassifier;
import dev.kastrick.minesport.export.BlockGeometryKind;
import dev.kastrick.minesport.export.BlockGrouper;
import dev.kastrick.minesport.export.LiquidGeometryBuilder;
import dev.kastrick.minesport.export.Quad;
import dev.kastrick.minesport.export.SpatialKey;
import dev.kastrick.minesport.region.BlockData;
import dev.kastrick.minesport.resolver.ResolverChain;
import dev.kastrick.minesport.resolver.RuntimeModelRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Export-mode GeometryBuilder wrapper used by IPC mode. */
public final class GeometryBuilder extends dev.kastrick.minesport.export.GeometryBuilder {
    private static final int[][] NEIGHBORS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private Map<Long,BlockData> worldIndex=Map.of();
    private final Map<String,BlockGeometryKind> kindCache=new HashMap<>();
    private final Map<String,RuntimeModelRegistry> runtimeRegistries=new ConcurrentHashMap<>();
    private final Set<String> failedRuntimeRegistries=ConcurrentHashMap.newKeySet();
    private final BlockGeometryClassifier classifier;
    private boolean hiddenBlockCullingEnabled;

    public GeometryBuilder(ResolverChain resolvers){
        super(resolvers);
        this.classifier=new BlockGeometryClassifier(resolvers);
        this.hiddenBlockCullingEnabled=readHiddenBlockCullingSetting();
    }

    @Override public void enableFaceCulling(List<BlockData> allBlocks){
        super.enableFaceCulling(allBlocks);
        buildWorldIndex(allBlocks);
    }

    /** Enables only the hidden-block visibility pass; does not enable face culling on surfaces. */
    public void enableHiddenBlockCulling(List<BlockData> allBlocks){
        hiddenBlockCullingEnabled=true;
        buildWorldIndex(allBlocks);
    }

    private void buildWorldIndex(List<BlockData> allBlocks){
        Map<Long,BlockData> index=new HashMap<>(Math.max(16,allBlocks.size()*2));
        for(BlockData b:allBlocks)if(!b.isAir())index.put(SpatialKey.of(b.x,b.y,b.z),b);
        worldIndex=index;
        kindCache.clear();
    }

    @Override public List<Quad> buildBlock(BlockData block){
        if(block==null||block.isAir())return List.of();
        if(hiddenBlockCullingEnabled&&!LiquidGeometryBuilder.isLiquid(block)&&isFullyEnclosed(block))return List.of();

        // Water/lava are FluidState-rendered in Minecraft and usually do not
        // have ordinary block-model JSON. Handle them before resolver fallback
        // geometry so they never become magenta full cubes.
        if(LiquidGeometryBuilder.isLiquid(block)){
            return LiquidGeometryBuilder.build(block,worldIndex);
        }

        // For registered modded/custom blocks, prefer the exact baked quads that
        // Minecraft produced during runtime-registry capture. Texture pixels are
        // still resolved later by the normal ResolverChain using quad.texturePath().
        RuntimeModelRegistry runtime=runtimeRegistry(block.runtimeRegistryPath);
        if(runtime!=null&&runtime.shouldOverride(block)){
            List<Quad> baked=runtime.build(block);
            if(baked!=null&&!baked.isEmpty())return baked;
        }
        return super.buildBlock(block);
    }

    private RuntimeModelRegistry runtimeRegistry(String path){
        if(path==null||path.isBlank()||failedRuntimeRegistries.contains(path))return null;
        RuntimeModelRegistry cached=runtimeRegistries.get(path);
        if(cached!=null)return cached;
        RuntimeModelRegistry loaded=RuntimeModelRegistry.load(new File(path),"",null);
        if(loaded==null){
            failedRuntimeRegistries.add(path);
            return null;
        }
        RuntimeModelRegistry previous=runtimeRegistries.putIfAbsent(path,loaded);
        return previous!=null?previous:loaded;
    }

    private boolean isFullyEnclosed(BlockData block){
        if(worldIndex.isEmpty())return false;
        for(int[] d:NEIGHBORS){
            BlockData neighbor=worldIndex.get(SpatialKey.of(block.x+d[0],block.y+d[1],block.z+d[2]));
            if(neighbor==null||neighbor.isAir())return false;
            // Geometry-only FULL_BLOCK classification is not enough for glass.
            // Transparent full cubes must never hide the block behind them.
            if(isTransparentOccluder(neighbor))return false;
            if(classify(neighbor)!=BlockGeometryKind.FULL_BLOCK)return false;
        }
        return true;
    }

    private static boolean isTransparentOccluder(BlockData block){
        if(block==null||block.blockId==null)return false;
        String id=block.blockId.toLowerCase(Locale.ROOT);
        return LiquidGeometryBuilder.isLiquid(block)
            || id.equals("minecraft:glass")
            || id.equals("minecraft:tinted_glass")
            || id.endsWith("_stained_glass")
            || id.endsWith("_glass_pane")
            || id.contains(":glass_");
    }

    private BlockGeometryKind classify(BlockData block){
        String key=block.blockId+"["+BlockGrouper.stateKey(block.properties)+"]";
        return kindCache.computeIfAbsent(key,ignored->classifier.classify(block));
    }

    private static boolean readHiddenBlockCullingSetting(){
        try{
            Path settings;
            String os=System.getProperty("os.name","").toLowerCase();
            if(os.contains("win")){
                String appData=System.getenv("APPDATA");
                if(appData==null||appData.isBlank())return false;
                settings=Path.of(appData,"minesport","settings.json");
            }else if(os.contains("mac")){
                settings=Path.of(System.getProperty("user.home"),"Library","Application Support","minesport","settings.json");
            }else{
                String xdg=System.getenv("XDG_CONFIG_HOME");
                Path root=(xdg==null||xdg.isBlank())?Path.of(System.getProperty("user.home"),".config"):Path.of(xdg);
                settings=root.resolve("minesport").resolve("settings.json");
            }
            if(!Files.isRegularFile(settings))return false;
            JsonObject obj=JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
            return obj.has("hiddenBlockCullingEnabled")&&obj.get("hiddenBlockCullingEnabled").getAsBoolean();
        }catch(Exception ignored){
            return false;
        }
    }
}
