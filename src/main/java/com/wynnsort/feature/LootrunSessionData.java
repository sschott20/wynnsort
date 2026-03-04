package com.wynnsort.feature;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds per-session data for a single lootrun run.
 * Tracked from lootrun start until completion or failure.
 */
public class LootrunSessionData {

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

    public LootrunSessionData() {
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Records a beacon selection of the given color name (e.g. "ORANGE", "RAINBOW", "AQUA").
     */
    public void recordBeacon(String colorName, boolean vibrant) {
        beaconsSelected++;
        beaconCounts.merge(colorName, 1, Integer::sum);
        if (vibrant) {
            vibrantCount++;
        }
    }

    /**
     * Returns a compact beacon summary string, e.g. "3O 1R 2B 1A".
     */
    public String getBeaconSummary() {
        if (beaconCounts.isEmpty()) return "none";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : beaconCounts.entrySet()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(entry.getValue());
            // Use first letter of the beacon color name
            String key = entry.getKey();
            sb.append(key.isEmpty() ? "?" : key.charAt(0));
        }
        return sb.toString();
    }

    /**
     * Returns the duration of the run in seconds. If the run is still active, uses current time.
     */
    public long getDurationSeconds() {
        long end = endTime > 0 ? endTime : System.currentTimeMillis();
        return (end - startTime) / 1000;
    }

    /**
     * Returns a formatted duration string like "5m 30s".
     */
    public String getFormattedDuration() {
        long totalSeconds = getDurationSeconds();
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
