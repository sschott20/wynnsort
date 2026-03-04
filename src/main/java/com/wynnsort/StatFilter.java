package com.wynnsort;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed filter condition like "healthRegen > 90".
 * When no operator is present, it's a simple stat display (show percentage).
 */
public record StatFilter(String statPattern, Operator op, float threshold) {

    private static final Pattern FILTER_PATTERN =
            Pattern.compile("\\s*([\\w\\s]+?)\\s*(>=|<=|>|<)\\s*(\\d+(?:\\.\\d+)?)\\s*");

    public enum Operator {
        GT, GTE, LT, LTE, NONE;

        public boolean test(float value, float threshold) {
            return switch (this) {
                case GT -> value > threshold;
                case GTE -> value >= threshold;
                case LT -> value < threshold;
                case LTE -> value <= threshold;
                case NONE -> true;
            };
        }
    }

    public boolean hasThreshold() {
        return op != Operator.NONE;
    }

    public boolean passes(float percentage) {
        return op.test(percentage, threshold);
    }

    /**
     * Parses an input string into a list of StatFilters.
     * Examples:
     *   "" -> empty list (overall mode)
     *   "air" -> [StatFilter("air", NONE, 0)]
     *   "healthRegen > 90" -> [StatFilter("healthRegen", GT, 90)]
     *   "healthRegen > 90, manaRegen > 80" -> two filters
     */
    public static List<StatFilter> parse(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }

        List<StatFilter> filters = new ArrayList<>();
        String[] parts = input.split(",");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            Matcher m = FILTER_PATTERN.matcher(trimmed);
            if (m.matches()) {
                String stat = m.group(1).trim();
                Operator op = parseOperator(m.group(2));
                float val = Float.parseFloat(m.group(3));
                filters.add(new StatFilter(stat, op, val));
            } else {
                // No operator — simple stat name for percentage display
                filters.add(new StatFilter(trimmed, Operator.NONE, 0));
            }
        }
        return filters;
    }

    private static Operator parseOperator(String op) {
        return switch (op) {
            case ">" -> Operator.GT;
            case ">=" -> Operator.GTE;
            case "<" -> Operator.LT;
            case "<=" -> Operator.LTE;
            default -> Operator.NONE;
        };
    }
}
