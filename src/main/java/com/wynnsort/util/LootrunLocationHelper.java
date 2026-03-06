package com.wynnsort.util;

import com.wynntils.core.components.Models;
import com.wynntils.models.lootrun.type.LootrunLocation;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/**
 * Isolated helper for accessing the current lootrun location from Wynntils.
 * The LootrunModel tracks task locations internally in a
 * {@code Map<LootrunLocation, Set<TaskLocation>>} field named "taskLocations".
 * Since there's no public getter for the current location, we use reflection.
 *
 * This class is loaded via try/catch to avoid crashing if the API changes.
 */
public final class LootrunLocationHelper {
    private LootrunLocationHelper() {}

    private static Field taskLocationsField;
    private static boolean fieldLookupDone = false;

    /**
     * Attempts to determine the current lootrun location.
     * Returns the LootrunLocation name (e.g. "SILENT_EXPANSE") or null if unavailable.
     */
    @SuppressWarnings("unchecked")
    public static String getCurrentLocationName() {
        try {
            if (!fieldLookupDone) {
                fieldLookupDone = true;
                taskLocationsField = Models.Lootrun.getClass().getDeclaredField("taskLocations");
                taskLocationsField.setAccessible(true);
            }
            if (taskLocationsField == null) return null;

            Map<LootrunLocation, ?> taskLocs =
                    (Map<LootrunLocation, ?>) taskLocationsField.get(Models.Lootrun);
            if (taskLocs == null || taskLocs.isEmpty()) return null;

            // The map typically has one entry during a lootrun — the current location.
            // If multiple exist, return the first with non-empty task set.
            for (Map.Entry<LootrunLocation, ?> entry : taskLocs.entrySet()) {
                if (entry.getValue() instanceof Set<?> set && !set.isEmpty()) {
                    return formatLocationName(entry.getKey().name());
                }
            }
            // Fallback: just return the first key
            return formatLocationName(taskLocs.keySet().iterator().next().name());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts enum name like "SILENT_EXPANSE" to "Silent Expanse".
     */
    public static String formatLocationName(String enumName) {
        if (enumName == null || enumName.isEmpty()) return enumName;
        if ("UNKNOWN".equals(enumName)) return null;

        String[] parts = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(part.charAt(0));
            sb.append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
