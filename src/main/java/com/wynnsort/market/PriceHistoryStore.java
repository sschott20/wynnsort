package com.wynnsort.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PriceHistoryStore {

    private static final FeatureLogger LOG = new FeatureLogger("PxHist", DiagnosticLog.Category.PERSISTENCE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("price_history.json");
    private static final Type MAP_TYPE =
            new TypeToken<ConcurrentHashMap<String, List<PriceHistoryEntry>>>() {}.getType();

    private static final int MAX_ENTRIES_PER_ITEM = 100;
    private static final long DEDUP_WINDOW_MS = 3_600_000L; // 1 hour

    private static final ConcurrentHashMap<String, List<PriceHistoryEntry>> history = new ConcurrentHashMap<>();
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static ScheduledExecutorService ioExecutor;

    public static void load() {
        if (Files.exists(STORE_PATH)) {
            try (Reader reader = Files.newBufferedReader(STORE_PATH)) {
                ConcurrentHashMap<String, List<PriceHistoryEntry>> loaded = GSON.fromJson(reader, MAP_TYPE);
                if (loaded != null) {
                    history.clear();
                    // Wrap each loaded list in a synchronized list
                    loaded.forEach((key, list) -> {
                        history.put(key, Collections.synchronizedList(new ArrayList<>(list)));
                    });
                    LOG.info("Loaded price history for {} items from {}", history.size(), STORE_PATH);
                    LOG.event("store_loaded", Map.of("count", history.size()));
                }
            } catch (IOException e) {
                LOG.error("Failed to load price history", e);
            }
        }

        ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WynnSort-PriceHistoryIO");
            t.setDaemon(true);
            return t;
        });
        ioExecutor.scheduleWithFixedDelay(PriceHistoryStore::saveIfDirty, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Records a price observation. Deduplicates same price within 1 hour.
     */
    public static void record(String itemName, long price, long timestamp) {
        if (!WynnSortConfig.INSTANCE.priceHistoryEnabled) return;
        if (itemName == null || itemName.isEmpty() || price <= 0) return;

        history.compute(itemName, (key, entries) -> {
            if (entries == null) {
                entries = Collections.synchronizedList(new ArrayList<>());
            }

            // Deduplicate: don't add if same price was recorded within the last hour
            synchronized (entries) {
                for (int i = entries.size() - 1; i >= 0; i--) {
                    PriceHistoryEntry existing = entries.get(i);
                    if (Math.abs(timestamp - existing.timestamp) <= DEDUP_WINDOW_MS
                            && existing.price == price) {
                        return entries; // Skip duplicate
                    }
                    // Entries before this are older, no need to check further
                    if (timestamp - existing.timestamp > DEDUP_WINDOW_MS) {
                        break;
                    }
                }

                entries.add(new PriceHistoryEntry(price, timestamp));

                // Sort by timestamp
                entries.sort(Comparator.comparingLong(e -> e.timestamp));

                // Trim to max entries (keep most recent)
                while (entries.size() > MAX_ENTRIES_PER_ITEM) {
                    entries.remove(0);
                }
            }

            dirty.set(true);
            return entries;
        });
    }

    /**
     * Returns the price history for an item, sorted by timestamp.
     */
    public static List<PriceHistoryEntry> getHistory(String itemName) {
        if (itemName == null || itemName.isEmpty()) return Collections.emptyList();
        List<PriceHistoryEntry> entries = history.get(itemName);
        if (entries == null) return Collections.emptyList();
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /**
     * Computes price statistics for an item.
     */
    public static PriceStats getStats(String itemName) {
        List<PriceHistoryEntry> entries = getHistory(itemName);
        if (entries.isEmpty()) return null;

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;

        long[] prices = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            long p = entries.get(i).price;
            prices[i] = p;
            if (p < min) min = p;
            if (p > max) max = p;
            sum += p;
        }

        long avg = sum / entries.size();

        // Median
        long[] sorted = prices.clone();
        Arrays.sort(sorted);
        long median;
        int n = sorted.length;
        if (n % 2 == 0) {
            median = (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        } else {
            median = sorted[n / 2];
        }

        long latestPrice = entries.get(entries.size() - 1).price;

        // Trend: compare avg of last 5 vs previous 5
        PriceTrend trend = computeTrend(entries);

        return new PriceStats(min, max, avg, median, entries.size(), trend, latestPrice);
    }

    private static PriceTrend computeTrend(List<PriceHistoryEntry> entries) {
        if (entries.size() < 5) return PriceTrend.UNKNOWN;

        int size = entries.size();
        int recentCount = Math.min(5, size);
        int previousStart = Math.max(0, size - 10);
        int previousEnd = size - recentCount;

        if (previousEnd <= previousStart) return PriceTrend.UNKNOWN;

        double recentAvg = 0;
        for (int i = size - recentCount; i < size; i++) {
            recentAvg += entries.get(i).price;
        }
        recentAvg /= recentCount;

        double previousAvg = 0;
        int previousCount = previousEnd - previousStart;
        for (int i = previousStart; i < previousEnd; i++) {
            previousAvg += entries.get(i).price;
        }
        previousAvg /= previousCount;

        if (previousAvg == 0) return PriceTrend.UNKNOWN;

        double changePercent = (recentAvg - previousAvg) / previousAvg;

        if (changePercent > 0.10) return PriceTrend.RISING;
        if (changePercent < -0.10) return PriceTrend.FALLING;
        return PriceTrend.STABLE;
    }

    private static void saveIfDirty() {
        // Evict entries older than configured max days
        long now = System.currentTimeMillis();
        long maxAgeMs = WynnSortConfig.INSTANCE.priceHistoryMaxDays * 24L * 3_600_000L;

        history.forEach((key, entries) -> {
            synchronized (entries) {
                boolean removed = entries.removeIf(e -> (now - e.timestamp) > maxAgeMs);
                if (removed) dirty.set(true);
            }
        });

        // Remove items with no entries left
        boolean removedEmpty = history.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty();
            }
        });
        if (removedEmpty) dirty.set(true);

        if (!dirty.compareAndSet(true, false)) return;
        saveToDisk();
    }

    private static void saveToDisk() {
        try {
            Files.createDirectories(STORE_PATH.getParent());

            // Create a snapshot for serialization
            Map<String, List<PriceHistoryEntry>> snapshot = new HashMap<>();
            history.forEach((key, entries) -> {
                synchronized (entries) {
                    snapshot.put(key, new ArrayList<>(entries));
                }
            });

            try (Writer writer = Files.newBufferedWriter(STORE_PATH)) {
                GSON.toJson(snapshot, writer);
            }
            LOG.info("Saved price history for {} items to {}", snapshot.size(), STORE_PATH);
            LOG.event("store_saved", Map.of("count", snapshot.size()));
        } catch (IOException e) {
            LOG.error("Failed to save price history", e);
        }
    }
}
