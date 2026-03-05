package com.wynnsort.feature;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LootrunSessionData {

    /** Beacon display order for summary: most impactful beacons first. */
    private static final List<String> BEACON_ORDER = Arrays.asList(
            "RAINBOW", "CRIMSON", "PURPLE", "BLUE", "YELLOW",
            "AQUA", "GREEN", "ORANGE", "RED", "GRAY", "DARK_GRAY", "WHITE"
    );

    /** 2-character abbreviations for beacon colors. */
    private static final Map<String, String> BEACON_ABBREV = new LinkedHashMap<>();
    static {
        BEACON_ABBREV.put("GREEN", "Gn");
        BEACON_ABBREV.put("YELLOW", "Yl");
        BEACON_ABBREV.put("BLUE", "Bl");
        BEACON_ABBREV.put("PURPLE", "Pr");
        BEACON_ABBREV.put("GRAY", "Gy");
        BEACON_ABBREV.put("ORANGE", "Or");
        BEACON_ABBREV.put("RED", "Rd");
        BEACON_ABBREV.put("DARK_GRAY", "DG");
        BEACON_ABBREV.put("WHITE", "Wh");
        BEACON_ABBREV.put("AQUA", "Aq");
        BEACON_ABBREV.put("CRIMSON", "Cr");
        BEACON_ABBREV.put("RAINBOW", "RB");
    }
    public long startTime;
    public int challengesCompleted;
    public int challengesFailed;
    public int beaconsSelected;
    public final Map<String, Integer> beaconCounts = new LinkedHashMap<>();
    public int vibrantCount;
    public int pullsEarned;
    public int rerollsEarned;
    public int sacrifices;
    public int mobsKilled;
    public int chestsOpened;
    public long xpEarned;
    public boolean completed;
    public long endTime;

    public LootrunSessionData() { this.startTime = System.currentTimeMillis(); }

    public void recordBeacon(String colorName, boolean vibrant) {
        beaconsSelected++;
        beaconCounts.merge(colorName, 1, Integer::sum);
        if (vibrant) { vibrantCount++; }
    }

    /**
     * Returns the total number of item chances: each pull gives (rerolls + 1) item views.
     */
    public int getEffectivePulls() {
        return pullsEarned * (rerollsEarned + 1);
    }

    /**
     * Returns a compact beacon summary using 2-char abbreviations, sorted in a defined order.
     * Example: "2Pr 1Bl 3Yl" instead of "2P 1B 3Y".
     */
    public String getBeaconSummary() {
        if (beaconCounts.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        // Iterate in defined order so output is consistent
        for (String beaconName : BEACON_ORDER) {
            Integer count = beaconCounts.get(beaconName);
            if (count == null || count <= 0) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(count);
            sb.append(BEACON_ABBREV.getOrDefault(beaconName, beaconName.substring(0, Math.min(2, beaconName.length()))));
        }
        // Include any beacons not in the predefined order (e.g., UNKNOWN)
        for (Map.Entry<String, Integer> entry : beaconCounts.entrySet()) {
            if (BEACON_ORDER.contains(entry.getKey())) continue;
            if (entry.getValue() <= 0) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(entry.getValue());
            String key = entry.getKey();
            sb.append(BEACON_ABBREV.getOrDefault(key, key.isEmpty() ? "?" : key.substring(0, Math.min(2, key.length()))));
        }
        return sb.toString();
    }

    public long getDurationSeconds() {
        long end = endTime > 0 ? endTime : System.currentTimeMillis();
        return (end - startTime) / 1000;
    }

    public String getFormattedDuration() {
        long totalSeconds = getDurationSeconds();
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) { return minutes + "m " + seconds + "s"; }
        return seconds + "s";
    }
}
