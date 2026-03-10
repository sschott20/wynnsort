package com.wynnsort.util;

/**
 * Represents one available weighted scale for scoring gear items.
 *
 * @param displayName user-facing label (e.g., "Lightbender", "Nori: Main")
 * @param sourceName  the ItemWeightSource name ("WYNNPOOL" or "NORI")
 * @param weightName  the raw weightName from Wynntils (e.g., "Main Scale", "Lightbender Scale")
 */
public record ScaleOption(String displayName, String sourceName, String weightName) {

    /** Special scale that uses GearInstance.getOverallPercentage() */
    public static final ScaleOption OVERALL = new ScaleOption("Overall", "", "Overall");

    /**
     * Config key stored in WynnSortConfig.selectedScale.
     * Format: "sourceName:weightName" for weighted scales, or "Overall" for overall.
     */
    public String configKey() {
        if (this == OVERALL || "Overall".equals(weightName)) return "Overall";
        return sourceName + ":" + weightName;
    }

    /**
     * Parses a config key back into source name and weight name.
     * Returns null if the key is "Overall" or malformed.
     */
    public static String[] parseConfigKey(String configKey) {
        if (configKey == null || configKey.isEmpty() || "Overall".equals(configKey)) return null;
        int idx = configKey.indexOf(':');
        if (idx < 0) return null;
        return new String[]{configKey.substring(0, idx), configKey.substring(idx + 1)};
    }
}
