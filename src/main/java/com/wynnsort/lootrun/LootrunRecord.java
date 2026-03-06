package com.wynnsort.lootrun;

import java.util.HashMap;
import java.util.Map;

/**
 * A single lootrun run record, capturing the outcome and stats of a completed or failed lootrun.
 */
public class LootrunRecord {

    public long startTime;
    public long endTime;
    /** true = completed successfully, false = failed */
    public boolean completed;
    public int challengesCompleted;
    public int pullsEarned;
    public int rerollsEarned;
    public int sacrifices;
    public int mobsKilled;
    public int chestsOpened;
    /** Beacon type name -> count of times selected */
    public Map<String, Integer> beaconCounts;
    /** Number of vibrant (rainbow-boosted) beacons selected */
    public int vibrantBeacons;
    /** Lootrun location name (e.g. "Silent Expanse"), null if unknown */
    public String location;
    /** Number of reward chests opened during the lootrun */
    public int rewardChestsOpened;
    /** Total items looted from reward chests (counts stack sizes for stackable items). */
    public int itemsLooted;

    public LootrunRecord() {
        this.beaconCounts = new HashMap<>();
    }

    /**
     * Duration of the run in seconds.
     */
    public int getDurationSeconds() {
        if (endTime <= 0 || startTime <= 0) return 0;
        return (int) ((endTime - startTime) / 1000);
    }
}
