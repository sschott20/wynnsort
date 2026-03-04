package com.wynnsort.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MarketPriceStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("market_prices.json");
    private static final Type MAP_TYPE = new TypeToken<ConcurrentHashMap<String, MarketPriceEntry>>() {}.getType();

    private static final ConcurrentHashMap<String, MarketPriceEntry> prices = new ConcurrentHashMap<>();
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static ScheduledExecutorService ioExecutor;

    public static void load() {
        if (Files.exists(STORE_PATH)) {
            try (Reader reader = Files.newBufferedReader(STORE_PATH)) {
                ConcurrentHashMap<String, MarketPriceEntry> loaded = GSON.fromJson(reader, MAP_TYPE);
                if (loaded != null) {
                    prices.clear();
                    prices.putAll(loaded);
                    WynnSortMod.log("Loaded {} market prices from {}", prices.size(), STORE_PATH);
                }
            } catch (IOException e) {
                WynnSortMod.logError("Failed to load market prices", e);
            }
        }

        ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WynnSort-MarketIO");
            t.setDaemon(true);
            return t;
        });
        ioExecutor.scheduleWithFixedDelay(MarketPriceStore::saveIfDirty, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Records a price observation. Only updates if cheaper than existing or if existing is stale.
     */
    public static void record(String baseName, long unitPrice, long timestamp) {
        if (baseName == null || baseName.isEmpty() || unitPrice <= 0) return;

        prices.compute(baseName, (key, existing) -> {
            if (existing == null || unitPrice <= existing.price || isStale(existing, timestamp)) {
                dirty.set(true);
                return new MarketPriceEntry(unitPrice, timestamp);
            }
            return existing;
        });
    }

    /**
     * Returns the cached price entry, or null if not found or stale.
     */
    public static MarketPriceEntry getPrice(String baseName) {
        if (baseName == null || baseName.isEmpty()) return null;
        MarketPriceEntry entry = prices.get(baseName);
        if (entry == null) return null;
        if (isStale(entry, System.currentTimeMillis())) return null;
        return entry;
    }

    private static boolean isStale(MarketPriceEntry entry, long now) {
        long stalenessMs = WynnSortConfig.INSTANCE.marketPriceStalenessHours * 3_600_000L;
        return (now - entry.timestamp) > stalenessMs;
    }

    private static void saveIfDirty() {
        // Evict stale entries periodically
        long now = System.currentTimeMillis();
        boolean evicted = prices.entrySet().removeIf(e -> isStale(e.getValue(), now));
        if (evicted) dirty.set(true);

        if (!dirty.compareAndSet(true, false)) return;
        saveToDisk();
    }

    private static void saveToDisk() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(STORE_PATH)) {
                GSON.toJson(prices, MAP_TYPE, writer);
            }
            WynnSortMod.log("Saved {} market prices to {}", prices.size(), STORE_PATH);
        } catch (IOException e) {
            WynnSortMod.logError("Failed to save market prices", e);
        }
    }
}
