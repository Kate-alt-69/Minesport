package dev.kastrick.minesport.export;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

/** Runtime gate for the optional FLATTER geometry compiler. */
public final class FlatterSettings {
    private static final String ENV = "MINESPORT_FLATTER";
    private static final String PROPERTY = "minesport.flatter";

    private FlatterSettings() {}

    public static boolean enabled() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) return parseBoolean(property);

        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) return parseBoolean(env);

        File settings = settingsFile();
        if (settings == null || !settings.isFile()) return false;
        try (Reader reader = new FileReader(settings)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return root.has("flatterOptimizationEnabled")
                && root.get("flatterOptimizationEnabled").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    static File settingsFile() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData == null || appData.isBlank()
                ? null
                : new File(new File(appData, "minesport"), "settings.json");
        }
        if (os.contains("mac")) {
            if (home.isBlank()) return null;
            return new File(new File(new File(home, "Library/Application Support"), "minesport"), "settings.json");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        File base = xdg != null && !xdg.isBlank()
            ? new File(xdg)
            : home.isBlank() ? null : new File(home, ".config");
        return base == null ? null : new File(new File(base, "minesport"), "settings.json");
    }

    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }
}
