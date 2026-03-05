package com.wynnsort.history;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransactionStore {

    private static final FeatureLogger LOG = new FeatureLogger("TxStore", DiagnosticLog.Category.PERSISTENCE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("transactions.json");
    private static final Type LIST_TYPE = new TypeToken<List<TransactionRecord>>() {}.getType();
    private static final int MAX_ENTRIES = 5000;
    private static final long DEDUP_WINDOW_MS = 10_000L;
    private static final int DEDUP_LOOKBACK = 5;

    private static final CopyOnWriteArrayList<TransactionRecord> transactions = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean dirty = new AtomicBoolean(false);
    private static ScheduledExecutorService ioExecutor;

    public static void load() {
        if (Files.exists(STORE_PATH)) {
            try (Reader reader = Files.newBufferedReader(STORE_PATH)) {
                List<TransactionRecord> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) {
                    transactions.clear();
                    transactions.addAll(loaded);
                    LOG.info("Loaded {} transactions from {}", transactions.size(), STORE_PATH);
                    LOG.event("store_loaded", Map.of("count", transactions.size()));
                }
            } catch (IOException e) {
                LOG.error("Failed to load transactions", e);
            }
        }

        ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WynnSort-IO");
            t.setDaemon(true);
            return t;
        });
        ioExecutor.scheduleWithFixedDelay(TransactionStore::saveIfDirty, 5, 5, TimeUnit.SECONDS);
    }

    public static void addTransaction(TransactionRecord record) {
        // Dedup: check last N records for a match within the time window
        int size = transactions.size();
        int start = Math.max(0, size - DEDUP_LOOKBACK);
        for (int i = size - 1; i >= start; i--) {
            TransactionRecord existing = transactions.get(i);
            if (existing.type == record.type
                    && existing.priceEmeralds == record.priceEmeralds
                    && Math.abs(record.timestamp - existing.timestamp) < DEDUP_WINDOW_MS) {
                String existingName = existing.baseName != null ? existing.baseName : existing.itemName;
                String recordName = record.baseName != null ? record.baseName : record.itemName;
                if (existingName != null && existingName.equalsIgnoreCase(recordName)) {
                    LOG.warn("Skipping duplicate transaction: {} {} for {} emeralds",
                            record.type, record.itemName, record.priceEmeralds);
                    return;
                }
            }
        }

        transactions.add(record);
        while (transactions.size() > MAX_ENTRIES) {
            transactions.remove(0);
        }
        dirty.set(true);
        LOG.info("Logged transaction: {} {}x {} for {} emeralds",
                record.type, record.quantity, record.itemName, record.priceEmeralds);
    }

    public static void removeTransaction(TransactionRecord record) {
        if (transactions.remove(record)) {
            dirty.set(true);
            LOG.info("Removed transaction: {} {}", record.type, record.itemName);
        }
    }

    public static void clearTransactions() {
        transactions.clear();
        dirty.set(true);
        LOG.info("Cleared all transactions");
    }

    public static List<TransactionRecord> getTransactions() {
        return Collections.unmodifiableList(new ArrayList<>(transactions));
    }

    /**
     * Find a matching BUY record for an item being sold.
     * For identified gear (fingerprint != null): exact fingerprint match only.
     * For unid/non-gear (fingerprint == null): most recent buy with same baseName.
     */
    public static TransactionRecord findMatchingBuy(String baseName, String statFingerprint) {
        if (baseName == null || baseName.isEmpty()) return null;

        // Search newest first
        List<TransactionRecord> all = new ArrayList<>(transactions);
        Collections.reverse(all);

        if (statFingerprint != null && !statFingerprint.isEmpty()) {
            // Identified gear: exact fingerprint match (version-aware)
            for (TransactionRecord r : all) {
                if (r.type != TransactionRecord.Type.BUY) continue;
                if (fingerprintsMatch(statFingerprint, r.statFingerprint)) return r;
            }
            return null;
        }

        // Unid/non-gear: most recent buy with same baseName or itemName
        for (TransactionRecord r : all) {
            if (r.type != TransactionRecord.Type.BUY) continue;
            if (baseName.equalsIgnoreCase(r.baseName)
                    || baseName.equalsIgnoreCase(r.itemName)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Pair BUY and SELL records. Identified gear matches on fingerprint; unid/non-gear on baseName.
     * Returns paired entries (buy+sell or unpaired buy) and populates unpairedSells with leftovers.
     */
    public static List<TransactionPair> pairTransactions(List<TransactionRecord> records, int buyTaxPercent,
                                                          List<TransactionRecord> unpairedSells) {
        List<TransactionRecord> buys = new ArrayList<>();
        List<TransactionRecord> sells = new ArrayList<>();
        for (TransactionRecord r : records) {
            if (r.type == TransactionRecord.Type.BUY) buys.add(r);
            else sells.add(r);
        }

        Set<Integer> consumedBuys = new HashSet<>();
        List<TransactionPair> pairs = new ArrayList<>();

        // Process sells newest-first
        List<TransactionRecord> sellsDesc = new ArrayList<>(sells);
        sellsDesc.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        for (TransactionRecord sell : sellsDesc) {
            int bestIdx = -1;
            long bestTimestamp = -1;
            String sellFp = sell.statFingerprint;
            String sellBase = sell.baseName != null ? sell.baseName : sell.itemName;

            for (int i = 0; i < buys.size(); i++) {
                if (consumedBuys.contains(i)) continue;
                TransactionRecord buy = buys.get(i);
                if (buy.timestamp > sell.timestamp) continue;

                boolean match = false;
                if (sellFp != null && !sellFp.isEmpty()) {
                    match = fingerprintsMatch(sellFp, buy.statFingerprint);
                } else {
                    String buyBase = buy.baseName != null ? buy.baseName : buy.itemName;
                    match = sellBase != null && sellBase.equalsIgnoreCase(buyBase);
                }

                if (match && buy.timestamp > bestTimestamp) {
                    bestIdx = i;
                    bestTimestamp = buy.timestamp;
                }
            }

            if (bestIdx >= 0) {
                consumedBuys.add(bestIdx);
                pairs.add(new TransactionPair(buys.get(bestIdx), sell, buyTaxPercent));
            } else {
                unpairedSells.add(sell);
            }
        }

        // Unpaired buys
        for (int i = 0; i < buys.size(); i++) {
            if (!consumedBuys.contains(i)) {
                pairs.add(new TransactionPair(buys.get(i), null, buyTaxPercent));
            }
        }

        // Sort by sell timestamp (or buy timestamp if unpaired), newest first
        pairs.sort((a, b) -> {
            long tsA = a.sell != null ? a.sell.timestamp : a.buy.timestamp;
            long tsB = b.sell != null ? b.sell.timestamp : b.buy.timestamp;
            return Long.compare(tsB, tsA);
        });

        return pairs;
    }

    /**
     * Compares fingerprints in a version-aware way.
     * Strips "v1:" prefix from both sides before comparing, enabling v0/v1 cross-matching.
     */
    static boolean fingerprintsMatch(String a, String b) {
        if (a == null || b == null) return false;
        String strippedA = a.startsWith("v1:") ? a.substring(3) : a;
        String strippedB = b.startsWith("v1:") ? b.substring(3) : b;
        return strippedA.equals(strippedB);
    }

    private static void saveIfDirty() {
        if (!dirty.compareAndSet(true, false)) return;
        saveToDisk();
    }

    private static void saveToDisk() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(STORE_PATH)) {
                GSON.toJson(transactions, LIST_TYPE, writer);
            }
            LOG.info("Saved {} transactions to {}", transactions.size(), STORE_PATH);
            LOG.event("store_saved", Map.of("count", transactions.size()));
        } catch (IOException e) {
            LOG.error("Failed to save transactions", e);
        }
    }
}
