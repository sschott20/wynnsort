package com.wynnsort;

import java.util.List;

/**
 * Tracks the current filter/display mode.
 * Supports:
 *   "" -> overall quality
 *   "air" -> single stat percentage display
 *   "healthRegen > 90, manaRegen > 80" -> multi-stat filter mode
 */
public final class SortState {

    private static String rawInput = "";
    private static List<StatFilter> filters = List.of();
    private static boolean defaultSortApplied = false;

    private SortState() {}

    public static boolean isDefaultSortApplied() {
        return defaultSortApplied;
    }

    public static void setDefaultSortApplied(boolean applied) {
        defaultSortApplied = applied;
    }

    public static void resetDefaultSort() {
        defaultSortApplied = false;
    }

    public static String getRawInput() {
        return rawInput;
    }

    public static void setRawInput(String input) {
        rawInput = input == null ? "" : input;
        filters = StatFilter.parse(rawInput);
    }

    public static List<StatFilter> getFilters() {
        return filters;
    }

    public static boolean isOverall() {
        return filters.isEmpty();
    }

    public static boolean isFilterMode() {
        return filters.stream().anyMatch(StatFilter::hasThreshold);
    }

    // Legacy accessors for backward compat
    public static String getTargetStat() {
        return rawInput.trim();
    }

    public static void setTargetStat(String stat) {
        setRawInput(stat);
    }

    public static String getSortToken() {
        return isOverall() ? "sort:overall" : "sort:" + rawInput.trim();
    }
}
