package com.wynnsort.lootrun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.wynnsort.WynnSortMod;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent store for lootrun history records.
 * Uses the same async-save pattern as TransactionStore.
 */
public class LootrunStore {

    private static final FeatureLogger LOG = new FeatureLogger("LRStore", DiagnosticLog.Category.PERSISTENCE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("lootrun_history.json");
    private static final Type LIST_TYPE = new TypeToken<List<LootrunRecord>>() {}.getType();
    private static final int MAX_ENTRIES = 500;

    private static final CopyOnWriteArrayList<LootrunRecord> records = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static ScheduledExecutorService ioExecutor;

    public static void load() {
        if (Files.exists(STORE_PATH)) {
            try (Reader reader = Files.newBufferedReader(STORE_PATH)) {
                List<LootrunRecord> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) {
                    records.clear();
                    records.addAll(loaded);
                    LOG.info("Loaded {} lootrun records from {}", records.size(), STORE_PATH);
                    LOG.event("store_loaded", Map.of("count", records.size()));
                }
            } catch (IOException e) {
                LOG.error("Failed to load lootrun history", e);
            }
        }

        ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WynnSort-LootrunIO");
            t.setDaemon(true);
            return t;
        });
        ioExecutor.scheduleWithFixedDelay(LootrunStore::saveIfDirty, 5, 5, TimeUnit.SECONDS);
    }

    public static void addRecord(LootrunRecord record) {
        records.add(record);
        while (records.size() > MAX_ENTRIES) {
            records.remove(0);
        }
        dirty.set(true);
        LOG.info("Logged lootrun: completed={}, challenges={}, pulls={}, xp={}",
                record.completed, record.challengesCompleted, record.pullsEarned, record.xpEarned);
    }

    /**
     * Returns an unmodifiable snapshot of all records, sorted newest-first.
     */
    public static List<LootrunRecord> getRecords() {
        List<LootrunRecord> snapshot = new ArrayList<>(records);
        snapshot.sort((a, b) -> Long.compare(b.endTime, a.endTime));
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Compute aggregate lifetime stats across all recorded runs.
     */
    public static LifetimeStats getLifetimeStats() {
        LifetimeStats stats = new LifetimeStats();
        List<LootrunRecord> all = new ArrayList<>(records);

        stats.totalRuns = all.size();
        for (LootrunRecord r : all) {
            if (r.completed) {
                stats.completedRuns++;
            } else {
                stats.failedRuns++;
            }
            stats.totalPulls += r.pullsEarned * (r.rerollsEarned + 1);
            stats.totalRerolls += r.rerollsEarned;
            stats.totalSacrifices += r.sacrifices;
            stats.totalXp += r.xpEarned;
            stats.totalChallenges += r.challengesCompleted;
            stats.totalMobsKilled += r.mobsKilled;
            stats.totalChestsOpened += r.chestsOpened;
        }

        if (stats.totalRuns > 0) {
            stats.avgPullsPerRun = (double) stats.totalPulls / stats.totalRuns;
            stats.avgChallengesPerRun = (double) stats.totalChallenges / stats.totalRuns;
            stats.completionRate = (double) stats.completedRuns / stats.totalRuns * 100.0;
        }

        return stats;
    }

    private static void saveIfDirty() {
        if (!dirty.compareAndSet(true, false)) return;
        saveToDisk();
    }

    private static void saveToDisk() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(STORE_PATH)) {
                GSON.toJson(records, LIST_TYPE, writer);
            }
            LOG.info("Saved {} lootrun records to {}", records.size(), STORE_PATH);
            LOG.event("store_saved", Map.of("count", records.size()));
        } catch (IOException e) {
            LOG.error("Failed to save lootrun history", e);
        }
    }

    /**
     * Aggregate statistics across all recorded lootrun runs.
     */
    public static class LifetimeStats {
        public int totalRuns;
        public int completedRuns;
        public int failedRuns;
        public int totalPulls;
        public int totalRerolls;
        public int totalSacrifices;
        public long totalXp;
        public int totalChallenges;
        public int totalMobsKilled;
        public int totalChestsOpened;
        public double avgPullsPerRun;
        public double avgChallengesPerRun;
        public double completionRate;
    }
}
