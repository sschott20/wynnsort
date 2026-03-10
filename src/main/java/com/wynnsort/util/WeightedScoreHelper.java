package com.wynnsort.util;

import com.wynntils.core.components.Services;
import com.wynntils.models.gear.type.ItemWeightSource;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.services.itemweight.type.ItemWeighting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Isolated helper for weighted scoring. This class imports Wynntils Services/ItemWeight
 * APIs that may not exist in all Wynntils versions. By isolating these imports,
 * ScoreComputation can load even when these APIs are unavailable.
 */
public final class WeightedScoreHelper {
    private WeightedScoreHelper() {}

    /**
     * Original compute method - returns the first available weighted score.
     * Kept for backwards compatibility.
     */
    public static float compute(GearItem gearItem) {
        String itemName = gearItem.getItemInfo().name();
        for (ItemWeightSource source : new ItemWeightSource[]{
                ItemWeightSource.WYNNPOOL, ItemWeightSource.NORI}) {
            List<ItemWeighting> weightings =
                    Services.ItemWeight.getItemWeighting(itemName, source);
            if (!weightings.isEmpty()) {
                return Services.ItemWeight.calculateWeighting(
                        weightings.get(0), gearItem);
            }
        }
        return Float.NaN;
    }

    /**
     * Discovers all available scales for a given item name.
     * Scans both NORI and WYNNPOOL sources and collects unique scale options.
     * If the same weightName appears in both sources, prefix with source name.
     */
    public static List<ScaleOption> getAllScales(String itemName) {
        // Collect weightings per source
        Map<String, List<String>> weightNameToSources = new LinkedHashMap<>();

        for (ItemWeightSource source : new ItemWeightSource[]{
                ItemWeightSource.NORI, ItemWeightSource.WYNNPOOL}) {
            List<ItemWeighting> weightings =
                    Services.ItemWeight.getItemWeighting(itemName, source);
            for (ItemWeighting w : weightings) {
                String wName = w.weightName();
                weightNameToSources
                        .computeIfAbsent(wName, k -> new ArrayList<>())
                        .add(source.name());
            }
        }

        // Build ScaleOption list with disambiguation
        List<ScaleOption> scales = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : weightNameToSources.entrySet()) {
            String wName = entry.getKey();
            List<String> sources = entry.getValue();
            boolean needsPrefix = sources.size() > 1;

            for (String sourceName : sources) {
                // Strip " Scale" suffix for compact display
                String shortName = wName;
                if (shortName.endsWith(" Scale")) {
                    shortName = shortName.substring(0, shortName.length() - " Scale".length());
                }

                String displayName;
                if (needsPrefix) {
                    // Capitalize source name nicely
                    String srcLabel = sourceName.substring(0, 1).toUpperCase()
                            + sourceName.substring(1).toLowerCase();
                    displayName = srcLabel + ": " + shortName;
                } else {
                    displayName = shortName;
                }

                scales.add(new ScaleOption(displayName, sourceName, wName));
            }
        }

        return scales;
    }

    /**
     * Computes the weighted score for a gear item using a specific source and scale name.
     */
    public static float computeForScale(GearItem gearItem, String sourceName, String scaleName) {
        String itemName = gearItem.getItemInfo().name();
        ItemWeightSource source = ItemWeightSource.valueOf(sourceName);
        List<ItemWeighting> weightings =
                Services.ItemWeight.getItemWeighting(itemName, source);
        for (ItemWeighting w : weightings) {
            if (w.weightName().equals(scaleName)) {
                return Services.ItemWeight.calculateWeighting(w, gearItem);
            }
        }
        return Float.NaN;
    }
}
