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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for crowdsourced market data with two modes:
 *
 * 1. LOCAL MODE (default): All observations are saved to a local JSON file
 *    that serves as a personal price database. Aggregation stats are
 *    computed from this local data.
 *
 * 2. REMOTE MODE (optional): If the user configures crowdsourceApiUrl in
 *    the config, observations are also POSTed as a batch to that endpoint,
 *    and community aggregate data can be fetched via GET.
 *
 * Rate limiting: at most 1 remote request per 30 seconds.
 */
public class CrowdsourceClient {

    public static final CrowdsourceClient INSTANCE = new CrowdsourceClient();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path LOCAL_DATA_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("crowdsource_data.json");

    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<CrowdsourceEntry>>() {}.getType();
    private static final Type LOCAL_DB_TYPE = new TypeToken<ConcurrentHashMap<String, List<CrowdsourceEntry>>>() {}.getType();

    /** Local database: itemName -> list of observations */
    private final ConcurrentHashMap<String, List<CrowdsourceEntry>> localDb = new ConcurrentHashMap<>();

    /** Cache for community data fetched from remote API */
    private final ConcurrentHashMap<String, CommunityPriceData> communityCache = new ConcurrentHashMap<>();

    /** Rate limiting: timestamp of last remote request */
    private volatile long lastRemoteRequestTime = 0;
    private static final long RATE_LIMIT_MS = 30_000; // 30 seconds

    private HttpClient httpClient;

    private CrowdsourceClient() {}

    /**
     * Initializes the client: loads local database and creates HTTP client if needed.
     */
    public void init() {
        loadLocalDb();
        WynnSortMod.log("[WynnSort] CrowdsourceClient initialized with {} items in local DB", localDb.size());
    }

    /**
     * Submits a batch of entries to local storage (and optionally remote API).
     *
     * @param entries the observations to submit
     */
    public void submitBatch(List<CrowdsourceEntry> entries) {
        if (entries == null || entries.isEmpty()) return;

        // Always save to local DB
        int added = 0;
        for (CrowdsourceEntry entry : entries) {
            if (entry.itemName == null || entry.itemName.isEmpty()) continue;
            localDb.computeIfAbsent(entry.itemName, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(entry);
            added++;
        }

        if (added > 0) {
            saveLocalDb();
            WynnSortMod.log("[WynnSort] Crowdsource: saved {} entries to local DB ({} total items)",
                    added, localDb.size());
        }

        // Optionally submit to remote API
        String apiUrl = WynnSortConfig.INSTANCE.crowdsourceApiUrl;
        if (apiUrl != null && !apiUrl.isEmpty()) {
            submitToRemote(entries, apiUrl);
        }
    }

    /**
     * Fetches community price data for an item.
     * First checks the community cache, then tries remote API if configured.
     * Falls back to local aggregation if no remote data.
     *
     * @param itemName the item to look up
     * @return community price data, or null if no data available
     */
    public CommunityPriceData getCommunityData(String itemName) {
        if (itemName == null || itemName.isEmpty()) return null;

        // Check cache first (remote data)
        CommunityPriceData cached = communityCache.get(itemName);
        if (cached != null && !cached.isStale()) {
            return cached;
        }

        // Try remote fetch if configured
        String apiUrl = WynnSortConfig.INSTANCE.crowdsourceApiUrl;
        if (apiUrl != null && !apiUrl.isEmpty()) {
            CommunityPriceData remote = fetchFromRemote(itemName, apiUrl);
            if (remote != null) {
                communityCache.put(itemName, remote);
                return remote;
            }
        }

        return null;
    }

    /**
     * Computes aggregation stats from the local database for a given item.
     *
     * @param itemName the item to aggregate
     * @return local aggregation stats, or null if no data
     */
    public LocalAggregation getLocalAggregation(String itemName) {
        if (itemName == null || itemName.isEmpty()) return null;

        List<CrowdsourceEntry> observations = localDb.get(itemName);
        if (observations == null || observations.isEmpty()) return null;

        // Filter out stale entries (older than staleness config)
        long now = System.currentTimeMillis();
        long stalenessMs = WynnSortConfig.INSTANCE.marketPriceStalenessHours * 3_600_000L;

        List<CrowdsourceEntry> recent = new ArrayList<>();
        for (CrowdsourceEntry e : observations) {
            if ((now - e.timestamp) <= stalenessMs) {
                recent.add(e);
            }
        }

        if (recent.isEmpty()) return null;

        // Compute stats
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;
        long latestTimestamp = 0;

        List<Long> prices = new ArrayList<>();
        for (CrowdsourceEntry e : recent) {
            prices.add(e.listingPrice);
            sum += e.listingPrice;
            if (e.listingPrice < min) min = e.listingPrice;
            if (e.listingPrice > max) max = e.listingPrice;
            if (e.timestamp > latestTimestamp) latestTimestamp = e.timestamp;
        }

        long avg = sum / prices.size();

        // Median
        Collections.sort(prices);
        long median;
        int size = prices.size();
        if (size % 2 == 0) {
            median = (prices.get(size / 2 - 1) + prices.get(size / 2)) / 2;
        } else {
            median = prices.get(size / 2);
        }

        return new LocalAggregation(min, max, avg, median, prices.size(), latestTimestamp);
    }

    // ---- Local DB persistence ----

    private void loadLocalDb() {
        if (!Files.exists(LOCAL_DATA_PATH)) return;

        try (Reader reader = Files.newBufferedReader(LOCAL_DATA_PATH)) {
            ConcurrentHashMap<String, List<CrowdsourceEntry>> loaded = GSON.fromJson(reader, LOCAL_DB_TYPE);
            if (loaded != null) {
                localDb.clear();
                localDb.putAll(loaded);
            }
        } catch (IOException e) {
            WynnSortMod.logError("[WynnSort] Failed to load crowdsource local DB", e);
        }
    }

    private void saveLocalDb() {
        try {
            Files.createDirectories(LOCAL_DATA_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(LOCAL_DATA_PATH)) {
                GSON.toJson(localDb, LOCAL_DB_TYPE, writer);
            }
        } catch (IOException e) {
            WynnSortMod.logError("[WynnSort] Failed to save crowdsource local DB", e);
        }
    }

    // ---- Remote API ----

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }
        return httpClient;
    }

    private boolean isRateLimited() {
        long now = System.currentTimeMillis();
        if (now - lastRemoteRequestTime < RATE_LIMIT_MS) {
            return true;
        }
        lastRemoteRequestTime = now;
        return false;
    }

    private void submitToRemote(List<CrowdsourceEntry> entries, String apiUrl) {
        if (isRateLimited()) {
            WynnSortMod.log("[WynnSort] Crowdsource: rate limited, skipping remote submit");
            return;
        }

        try {
            String json = GSON.toJson(entries, ENTRY_LIST_TYPE);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/submit"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "WynnSort/1.0.0")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            getHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            WynnSortMod.log("[WynnSort] Crowdsource: submitted {} entries to remote API", entries.size());
                        } else {
                            WynnSortMod.logWarn("[WynnSort] Crowdsource: remote API returned status {}", response.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        WynnSortMod.logWarn("[WynnSort] Crowdsource: remote submit failed: {}", ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            WynnSortMod.logWarn("[WynnSort] Crowdsource: failed to submit to remote API: {}", e.getMessage());
        }
    }

    private CommunityPriceData fetchFromRemote(String itemName, String apiUrl) {
        if (isRateLimited()) {
            return null;
        }

        try {
            String encodedName = java.net.URLEncoder.encode(itemName, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/prices?item=" + encodedName))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "WynnSort/1.0.0")
                    .GET()
                    .build();

            HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                CommunityPriceData data = GSON.fromJson(response.body(), CommunityPriceData.class);
                if (data != null) {
                    data.fetchedAt = System.currentTimeMillis();
                    return data;
                }
            }
        } catch (Exception e) {
            WynnSortMod.logWarn("[WynnSort] Crowdsource: failed to fetch community data for '{}': {}",
                    itemName, e.getMessage());
        }
        return null;
    }

    /**
     * Flushes the local database to disk. Called during shutdown.
     */
    public void shutdown() {
        if (!localDb.isEmpty()) {
            saveLocalDb();
            WynnSortMod.log("[WynnSort] CrowdsourceClient shutdown: saved {} items", localDb.size());
        }
    }

    // ---- Inner data classes ----

    /**
     * Community price data from a remote API.
     */
    public static class CommunityPriceData {
        public long avgPrice;
        public long midEightyAvg;   // average of mid 80% of prices (excluding outliers)
        public long minPrice;
        public long maxPrice;
        public int listingCount;
        public long fetchedAt;      // local timestamp of when this was fetched

        /** Community data is considered stale after 5 minutes. */
        public boolean isStale() {
            return (System.currentTimeMillis() - fetchedAt) > 300_000;
        }
    }

    /**
     * Aggregation stats computed from local observations.
     */
    public static class LocalAggregation {
        public final long min;
        public final long max;
        public final long avg;
        public final long median;
        public final int count;
        public final long lastSeen;

        public LocalAggregation(long min, long max, long avg, long median, int count, long lastSeen) {
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.median = median;
            this.count = count;
            this.lastSeen = lastSeen;
        }
    }
}
