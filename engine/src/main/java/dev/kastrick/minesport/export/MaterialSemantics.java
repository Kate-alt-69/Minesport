package dev.kastrick.minesport.export;

import java.util.Locale;

/** Shared texture/material semantics used by OBJ/glTF and DCC translation. */
public final class MaterialSemantics {
    public enum Kind { DEFAULT, WATER, GLASS }

    private MaterialSemantics() {}

    public static Kind classify(String raw) {
        String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (value.contains("water_still") || value.contains("water_flow")
            || value.contains("block_water") || value.endsWith(":water")) {
            return Kind.WATER;
        }
        if (value.contains("glass") && !value.contains("glass_bottle")) {
            return Kind.GLASS;
        }
        return Kind.DEFAULT;
    }

    public static boolean isTranslucent(String raw) {
        return classify(raw) != Kind.DEFAULT;
    }

    /** OBJ dissolve fallback when the format has no physically based transmission. */
    public static double dissolve(String raw) {
        return switch (classify(raw)) {
            case WATER -> 0.68;
            case GLASS -> 0.72;
            default -> 1.0;
        };
    }

    public static double roughness(String raw) {
        return switch (classify(raw)) {
            case WATER -> 0.16;
            case GLASS -> 0.08;
            default -> 1.0;
        };
    }
}
