package dev.kastrick.minesport.export;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

/** Runtime settings for the optional FLATTER geometry compiler. */
public final class FlatterSettings {
    public static final int DEFAULT_CELL_SIZE = 16;

    private static final String ENV = "MINESPORT_FLATTER";
    private static final String PROPERTY = "minesport.flatter";
    private static final String CELL_ENV = "MINESPORT_FLATTER_CELL_SIZE";
    private static final String CELL_PROPERTY = "minesport.flatterCellSize";

    private FlatterSettings() {}

    public static boolean enabled() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) return parseBoolean(property);

        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) return parseBoolean(env);

        JsonObject root = readSettings();
        return root != null
            && root.has("flatterOptimizationEnabled")
            && root.get("flatterOptimizationEnabled").getAsBoolean();
    }

    public static int cellSize() {
        String property = System.getProperty(CELL_PROPERTY);
        if (property != null && !property.isBlank()) return parseCellSize(property);

        String env = System.getenv(CELL_ENV);
        if (env != null && !env.isBlank()) return parseCellSize(env);

        JsonObject root = readSettings();
        if (root != null && root.has("flatterCellSize")) {
            try {
                return normalizeCellSize(root.get("flatterCellSize").getAsInt());
            } catch (Exception ignored) {
                // Fall through to the stable default for old/corrupt settings.
            }
        }
        return DEFAULT_CELL_SIZE;
    }

    public static int normalizeCellSize(int value) {
        return switch (value) {
            case 8, 16, 32, 64 -> value;
            default -> DEFAULT_CELL_SIZE;
        };
    }

    private static int parseCellSize(String value) {
        try {
            return normalizeCellSize(Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return DEFAULT_CELL_SIZE;
        }
    }

    private static JsonObject readSettings() {
        File settings = settingsFile();
        if (settings == null || !settings.isFile()) return null;
        try (Reader reader = new FileReader(settings)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Canonical 0.2.x settings file shared by all Java engine settings readers. */
    public static File settingsFile() {
        String override = System.getenv("MINESPORT_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return new File(new File(override), "settings.json");
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isBlank()) return null;
            return new File(
                new File(new File(localAppData, "kastrick's_software"), "minesport"),
                "settings.json"
            );
        }
        if (os.contains("mac")) {
            if (home.isBlank()) return null;
            return new File(
                new File(new File(new File(home, "Library/Application Support"), "kastrick's_software"), "minesport"),
                "settings.json"
            );
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        File base = xdg != null && !xdg.isBlank()
            ? new File(xdg)
            : home.isBlank() ? null : new File(new File(home, ".local"), "share");
        if (base == null) return null;
        return new File(new File(new File(base, "kastrick's_software"), "minesport"), "settings.json");
    }

    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }
}
