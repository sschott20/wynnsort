package com.wynnsort.feature;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.lootrun.LootrunRecord;
import com.wynnsort.lootrun.LootrunStore;
import com.wynntils.models.lootrun.event.LootrunFinishedEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Subscribes to Wynntils' lootrun finished events and records each run
 * in LootrunStore for the history/analytics screen.
 */
public class LootrunHistoryFeature {

    public static final LootrunHistoryFeature INSTANCE = new LootrunHistoryFeature();

    private LootrunHistoryFeature() {}

    @SubscribeEvent
    public void onLootrunCompleted(LootrunFinishedEvent.Completed event) {
        if (!WynnSortConfig.INSTANCE.lootrunHistoryEnabled) return;

        try {
            long now = System.currentTimeMillis();

            LootrunRecord record = new LootrunRecord();
            record.completed = true;
            record.endTime = now;
            // timeElapsed is in seconds (from Wynntils)
            record.startTime = now - ((long) event.getTimeElapsed() * 1000L);
            record.challengesCompleted = event.getChallengesCompleted();
            record.pullsEarned = event.getRewardPulls();
            record.rerollsEarned = event.getRewardRerolls();
            record.sacrifices = event.getRewardSacrifices();
            record.xpEarned = event.getExperienceGained();
            record.mobsKilled = event.getMobsKilled();
            record.chestsOpened = event.getChestsOpened();

            LootrunStore.addRecord(record);

            WynnSortMod.log("[WynnSort] Lootrun COMPLETED: challenges={}, pulls={}, rerolls={}, sacrifices={}, xp={}, mobs={}, chests={}, time={}s",
                    record.challengesCompleted, record.pullsEarned, record.rerollsEarned,
                    record.sacrifices, record.xpEarned, record.mobsKilled,
                    record.chestsOpened, event.getTimeElapsed());
        } catch (Exception e) {
            WynnSortMod.logError("[WynnSort] Error recording completed lootrun", e);
        }
    }

    @SubscribeEvent
    public void onLootrunFailed(LootrunFinishedEvent.Failed event) {
        if (!WynnSortConfig.INSTANCE.lootrunHistoryEnabled) return;

        try {
            long now = System.currentTimeMillis();

            LootrunRecord record = new LootrunRecord();
            record.completed = false;
            record.endTime = now;
            record.startTime = now - ((long) event.getTimeElapsed() * 1000L);
            record.challengesCompleted = event.getChallengesCompleted();
            // Failed runs don't have reward data
            record.pullsEarned = 0;
            record.rerollsEarned = 0;
            record.sacrifices = 0;
            record.xpEarned = 0;
            record.mobsKilled = 0;
            record.chestsOpened = 0;

            LootrunStore.addRecord(record);

            WynnSortMod.log("[WynnSort] Lootrun FAILED: challenges={}, time={}s",
                    record.challengesCompleted, event.getTimeElapsed());
        } catch (Exception e) {
            WynnSortMod.logError("[WynnSort] Error recording failed lootrun", e);
        }
    }
}
