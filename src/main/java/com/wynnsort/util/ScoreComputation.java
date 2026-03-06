package com.wynnsort.util;

import com.wynnsort.SortState;
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
     * In overall mode, uses Wynntils' weighted score (Wynnpool/Nori) when available.
     */
    public static float computeScore(GearItem gearItem, GearInstance gearInstance,
                                     List<StatFilter> filters) {
        if (SortState.isOverall()) {
            // Items with no rollable stats return 0% from getOverallPercentage(),
            // which is misleading. Return NaN to indicate "not applicable".
            if (gearInstance.identifications() == null || gearInstance.identifications().isEmpty()) {
                return Float.NaN;
            }
            if (WynnSortConfig.INSTANCE.useWeightedScale) {
                float weighted = getWeightedScore(gearItem);
                if (!Float.isNaN(weighted) && weighted > 0.0f) return weighted;
            }
            return gearInstance.getOverallPercentage();
        } else if (SortState.isFilterMode()) {
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
     * Attempts to get a weighted quality score from Wynntils' ItemWeightService
     * (Wynnpool/Nori community weights). Returns NaN if no weight data is available.
     */
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
     * Finds a stat on the item matching the pattern and returns its roll percentage.
     * Matches against apiName, displayName, and key (case-insensitive contains).
     */
    public static float resolveStatPercentage(GearItem gearItem, GearInstance gearInstance,
                                              String pattern) {
        String target = pattern.toLowerCase().trim();

        for (StatActualValue actual : gearInstance.identifications()) {
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
