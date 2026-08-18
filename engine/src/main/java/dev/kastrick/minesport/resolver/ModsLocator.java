package dev.kastrick.minesport.resolver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the mods folder associated with a given Minecraft world.
 *
 * Strategy:
 *  1. Check sibling of the world's parent (world is usually in .minecraft/saves/WorldName,
 *     so mods is at .minecraft/mods)
 *  2. Walk up directory tree looking for a mods/ folder
 *  3. Check known launcher paths (FreesmLauncher instances)
 */
public class ModsLocator {

    public record LocatedMods(File modsFolder, String loaderType, String instanceName) {}

    /**
     * Try to find the mods folder for a given world folder.
     * World folder is e.g.:
     *   FreesmLauncher: .../FreesmLauncher/instances/1.21.10/minecraft/saves/MyWorld
     *   Standard:       .../.minecraft/saves/MyWorld
     */
    public static LocatedMods locate(File worldFolder) {
        // Walk up looking for a mods/ folder sibling
        File current = worldFolder;
        for (int i = 0; i < 5; i++) { // don't walk too far up
            current = current.getParentFile();
            if (current == null) break;

            File mods = new File(current, "mods");
            if (mods.exists() && mods.isDirectory()) {
                String loaderType = detectLoader(current);
                String instanceName = detectInstanceName(current);
                return new LocatedMods(mods, loaderType, instanceName);
            }
        }

        return null; // not found
    }

    /**
     * Detect which mod loader is in use by checking for loader-specific files
     * in the minecraft instance directory.
     */
    public static String detectLoader(File minecraftDir) {
        // Fabric: has .fabric/ folder or fabric-loader in libraries
        if (new File(minecraftDir, ".fabric").exists()) return "fabric";

        // Check mods folder for fabric.mod.json inside jars (expensive, skip for now)
        // Check libraries folder for loader hints
        File libs = new File(minecraftDir, "libraries");
        if (libs.exists()) {
            File fabricLoader = new File(libs, "net/fabricmc");
            if (fabricLoader.exists()) return "fabric";

            File forgeLoader = new File(libs, "net/minecraftforge");
            if (forgeLoader.exists()) return "forge";

            File neoForge = new File(libs, "net/neoforged");
            if (neoForge.exists()) return "neoforge";
        }

        // FreesmLauncher: check instance config
        File instanceCfg = new File(minecraftDir.getParentFile(), "instance.cfg");
        if (instanceCfg.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(instanceCfg.toPath()));
                if (content.contains("fabric")) return "fabric";
                if (content.contains("forge"))  return "forge";
                if (content.contains("quilt"))  return "quilt";
            } catch (Exception ignored) {}
        }

        return "unknown";
    }

    /**
     * Try to get a human-readable instance name from the instance folder.
     * For FreesmLauncher this is the version string in the path.
     */
    private static String detectInstanceName(File minecraftDir) {
        // FreesmLauncher: .../instances/<name>/minecraft → parent of minecraft dir
        File parent = minecraftDir.getParentFile();
        if (parent != null) {
            return parent.getName(); // e.g. "1.21.10"
        }
        return minecraftDir.getName();
    }

    /**
     * Build a list of candidate mods folders to try, in priority order.
     * Used when auto-detection fails.
     */
    public static List<File> candidatePaths(String version) {
        List<File> candidates = new ArrayList<>();

        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            // FreesmLauncher
            candidates.add(new File(appdata,
                "FreesmLauncher/instances/" + version + "/minecraft/mods"));
            // Standard .minecraft
            candidates.add(new File(appdata, ".minecraft/mods"));
        }

        // Linux
        String home = System.getProperty("user.home");
        candidates.add(new File(home, ".minecraft/mods"));

        // Mac
        candidates.add(new File(home, "Library/Application Support/minecraft/mods"));

        return candidates;
    }
}
