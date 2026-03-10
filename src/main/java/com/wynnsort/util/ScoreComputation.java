package com.wynnsort.util;

import com.wynnsort.StatFilter;
import com.wynnsort.config.WynnSortConfig;
import com.wynntils.models.gear.type.GearInfo;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.stats.StatCalculator;
import com.wynntils.models.stats.type.StatActualValue;
import com.wynntils.models.stats.type.StatPossibleValues;

import java.util.List;

public final class ScoreComputation {

    private ScoreComputation() {}

    /**
     * Computes the display score for a gear item given the current filter state.
     * In overall mode, uses the selected scale from config.
     */
    public static float computeScore(GearItem gearItem, GearInstance gearInstance,
                                     List<StatFilter> filters) {
        boolean overall = filters.isEmpty();
        boolean filterMode = !overall && filters.stream().anyMatch(StatFilter::hasThreshold);

        if (overall) {
            // Items with no rollable stats return 0% from getOverallPercentage(),
            // which is misleading. Return NaN to indicate "not applicable".
            List<StatActualValue> ids = gearInstance.identifications();
            if (ids == null || ids.isEmpty()) {
                return Float.NaN;
            }

            String selectedScale = WynnSortConfig.INSTANCE.selectedScale;
            if (selectedScale == null || selectedScale.isEmpty()) {
                selectedScale = "Overall";
            }

            if (!"Overall".equals(selectedScale)) {
                float weighted = getWeightedScoreForScale(gearItem, selectedScale);
                if (!Float.isNaN(weighted) && weighted > 0.0f) return weighted;
                // Fall through to overall if the selected scale isn't available for this item
            }
            return gearInstance.getOverallPercentage();
        } else if (filterMode) {
            float totalPct = 0f;
            int count = 0;
            for (StatFilter filter : filters) {
                float pct = resolveStatPercentage(gearItem, gearInstance, filter.statPattern());
                if (Float.isNaN(pct) || pct < 0.0f) return Float.NaN;
                if (filter.hasThreshold() && !filter.passes(pct)) return Float.NaN;
                totalPct += pct;
                count++;
            }
            return count == 0 ? Float.NaN : totalPct / count;
        } else {
            return resolveStatPercentage(gearItem, gearInstance, filters.get(0).statPattern());
        }
    }

    /**
     * Attempts to get a weighted quality score using the specified scale config key.
     * Config key format: "SOURCE:weightName" (e.g., "NORI:Main Scale").
     * Returns NaN if no weight data is available.
     */
    public static float getWeightedScoreForScale(GearItem gearItem, String scaleConfigKey) {
        try {
            String[] parts = ScaleOption.parseConfigKey(scaleConfigKey);
            if (parts == null) return Float.NaN;
            return WeightedScoreHelper.computeForScale(gearItem, parts[0], parts[1]);
        } catch (Throwable t) {
            // WeightedScoreHelper may fail to load if Wynntils version
            // doesn't have ItemWeight APIs
        }
        return Float.NaN;
    }

    /**
     * Attempts to get a weighted quality score from Wynntils' ItemWeightService
     * (Wynnpool/Nori community weights). Returns NaN if no weight data is available.
     * @deprecated Use getWeightedScoreForScale with a specific scale config key.
     */
    @Deprecated
    public static float getWeightedScore(GearItem gearItem) {
        try {
            return WeightedScoreHelper.compute(gearItem);
        } catch (Throwable t) {
            // WeightedScoreHelper may fail to load if Wynntils version
            // doesn't have ItemWeight APIs
        }
        return Float.NaN;
    }

    /**
     * Discovers all available scales for a given item name.
     * Wraps WeightedScoreHelper.getAllScales with try/catch for API isolation.
     */
    public static List<ScaleOption> getAllScales(String itemName) {
        try {
            return WeightedScoreHelper.getAllScales(itemName);
        } catch (Throwable t) {
            // WeightedScoreHelper may fail to load
        }
        return List.of();
    }

    /**
     * Finds a stat on the item matching the pattern and returns its roll percentage.
     * Matches against apiName, displayName, and key (case-insensitive contains).
     */
    public static float resolveStatPercentage(GearItem gearItem, GearInstance gearInstance,
                                              String pattern) {
        String target = pattern.toLowerCase().trim();

        List<StatActualValue> ids = gearInstance.identifications();
        if (ids == null) return Float.NaN;
        for (StatActualValue actual : ids) {
            if (statMatches(actual, target)) {
                GearInfo gearInfo = gearItem.getItemInfo();
                StatPossibleValues possible = gearInfo.getPossibleValues(actual.statType());
                if (possible != null) {
                    return StatCalculator.getPercentage(actual, possible);
                }
            }
        }

        return Float.NaN;
    }

    /**
     * Checks if a stat actual value matches a target string (case-insensitive).
     * Matches against apiName, displayName, and key -- exact match first, then contains.
     *
     * Note: contains-matching is intentionally broad but can be ambiguous.
     * For example, "dam" matches both "rawDamage" and "damageBonus".
     * Exact matches are checked first to prefer precise hits when possible.
     */
    public static boolean statMatches(StatActualValue actual, String target) {
        String apiName = actual.statType().getApiName().toLowerCase();
        String displayName = actual.statType().getDisplayName().toLowerCase();
        String key = actual.statType().getKey().toLowerCase();

        // Prefer exact match to avoid ambiguity from contains-matching
        if (apiName.equals(target) || displayName.equals(target) || key.equals(target)) {
            return true;
        }

        return apiName.contains(target) || displayName.contains(target) || key.contains(target);
    }
}
