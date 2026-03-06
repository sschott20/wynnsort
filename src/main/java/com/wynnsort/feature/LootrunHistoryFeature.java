package com.wynnsort.feature;

import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynnsort.lootrun.LootrunRecord;
import com.wynnsort.lootrun.LootrunStore;
import com.wynntils.models.lootrun.event.LootrunFinishedEvent;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Subscribes to Wynntils' lootrun finished events and records each run
 * in LootrunStore for the history/analytics screen.
 */
public class LootrunHistoryFeature {

    public static final LootrunHistoryFeature INSTANCE = new LootrunHistoryFeature();
    private static final FeatureLogger LOG = new FeatureLogger("LRHist", DiagnosticLog.Category.LOOTRUN);

    private LootrunHistoryFeature() {}

    private void copySessionData(LootrunRecord record) {
        LootrunSessionData session = LootrunSessionStats.INSTANCE.getCurrentSession();
        if (session == null) {
            session = LootrunSessionStats.INSTANCE.getLastSession();
        }
        if (session != null) {
            record.location = session.location;
            record.rewardChestsOpened = session.rewardChestsOpened;
            record.itemsLooted = session.itemsLooted;
        }
    }

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
            record.mobsKilled = event.getMobsKilled();
            record.chestsOpened = event.getChestsOpened();
            copySessionData(record);

            int durationSeconds = (int) ((record.endTime - record.startTime) / 1000);
            if (record.challengesCompleted == 0 && durationSeconds < 30) {
                LOG.info("Skipping trivial lootrun ({}s, 0 challenges)", durationSeconds);
                return;
            }

            LootrunStore.addRecord(record);

            LOG.info("Lootrun COMPLETED: challenges={}, pulls={}, rerolls={}, sacrifices={}, mobs={}, chests={}, rewardChests={}, items={}, location={}, time={}s",
                    record.challengesCompleted, record.pullsEarned, record.rerollsEarned,
                    record.sacrifices, record.mobsKilled,
                    record.chestsOpened, record.rewardChestsOpened, record.itemsLooted,
                    record.location, event.getTimeElapsed());

            Map<String, Object> evtData = new LinkedHashMap<>();
            evtData.put("completed", true);
            evtData.put("challenges", record.challengesCompleted);
            evtData.put("pulls", record.pullsEarned);
            evtData.put("rerolls", record.rerollsEarned);
            evtData.put("location", record.location);
            evtData.put("rewardChests", record.rewardChestsOpened);
            evtData.put("items", record.itemsLooted);
            evtData.put("timeSeconds", event.getTimeElapsed());
            LOG.event("run_recorded", evtData);
        } catch (Exception e) {
            LOG.error("Error recording completed lootrun", e);
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
            record.mobsKilled = 0;
            record.chestsOpened = 0;
            copySessionData(record);

            int durationSeconds = (int) ((record.endTime - record.startTime) / 1000);
            if (record.challengesCompleted == 0 && durationSeconds < 30) {
                LOG.info("Skipping trivial lootrun ({}s, 0 challenges)", durationSeconds);
                return;
            }

            LootrunStore.addRecord(record);

            LOG.info("Lootrun FAILED: challenges={}, location={}, time={}s",
                    record.challengesCompleted, record.location, event.getTimeElapsed());

            Map<String, Object> evtData = new LinkedHashMap<>();
            evtData.put("completed", false);
            evtData.put("challenges", record.challengesCompleted);
            evtData.put("location", record.location);
            evtData.put("timeSeconds", event.getTimeElapsed());
            LOG.event("run_recorded", evtData);
        } catch (Exception e) {
            LOG.error("Error recording failed lootrun", e);
        }
    }
}
