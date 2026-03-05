package com.wynnsort.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.wynnsort.WynnSortMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class WynnSortConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("wynnsort.json");

    public static WynnSortConfig INSTANCE = new WynnSortConfig();

    // --- Config fields ---

    public boolean overlayEnabled = true;
    public boolean showPercentageText = true;
    public boolean sortButtonEnabled = true;
    public String lastFilter = "";
    /** true = use Nori/Wynnpool weighted scale, false = use default overall % */
    public boolean useWeightedScale = true;
    /** Show orange beacon duration tracker during lootruns */
    public boolean lootrunHudEnabled = true;
    /** Show lootrun session statistics HUD during active runs */
    public boolean lootrunStatsHudEnabled = true;
    /** Record lootrun completion/failure history for analytics */
    public boolean lootrunHistoryEnabled = true;
    /** Log trade market transactions to disk */
    public boolean tradeHistoryEnabled = true;
    /** Minimum price filter for trade history display (emeralds) */
    public long tradeHistoryMinPriceFilter = 5000;
    /** Trade market buyer tax percentage (applied to BUY display prices) */
    public int tradeMarketBuyTaxPercent = 5;
    /** Verbose trade market logging (logs every state change, container, slot, chat) */
    public boolean tradeMarketLogging = true;
    /** Track mythic dry streak across lootrun sessions */
    public boolean dryStreakEnabled = true;
    /** Enable market price caching and tooltip display */
    public boolean marketPriceCacheEnabled = true;
    /** Hours before a cached market price is considered stale */
    public int marketPriceStalenessHours = 168;
    /** Enable saved search presets with quick-apply buttons */
    public boolean searchPresetsEnabled = true;
    /** Enable price history tracking with trend analysis */
    public boolean priceHistoryEnabled = true;
    /** Maximum number of days to retain price history entries */
    public int priceHistoryMaxDays = 30;
    /** Enable crowdsourced market data collection */
    public boolean crowdsourceEnabled = true;
    /** Remote API URL for crowdsource data (empty = local only) */
    public String crowdsourceApiUrl = "";
    /** Minutes between crowdsource queue flushes */
    public int crowdsourceFlushMinutes = 5;
    /** Enable structured diagnostic logging (JSONL events + in-game viewer) */
    public boolean diagnosticLoggingEnabled = true;

    // --- HUD positions (0.0-1.0 of screen, -1 = use default) ---

    /** Beacon tracker HUD X position (fraction of screen width, -1 = default) */
    public float beaconHudX = -1;
    /** Beacon tracker HUD Y position (fraction of screen height, -1 = default) */
    public float beaconHudY = -1;
    /** Session stats HUD X position (fraction of screen width, -1 = default) */
    public float sessionStatsHudX = -1;
    /** Session stats HUD Y position (fraction of screen height, -1 = default) */
    public float sessionStatsHudY = -1;
    /** Dry streak HUD X position (fraction of screen width, -1 = default) */
    public float dryStreakHudX = -1;
    /** Dry streak HUD Y position (fraction of screen height, -1 = default) */
    public float dryStreakHudY = -1;

    // --- HUD display toggles: Beacon Tracker ---

    public boolean showBeaconOrange = true;
    public boolean showBeaconRainbow = true;
    public boolean showBeaconGrey = true;
    public boolean showBeaconCrimson = true;

    // --- HUD display toggles: Session Stats ---

    public boolean showStatsChallenges = true;
    public boolean showStatsPullsRerolls = true;
    public boolean showStatsMythicChance = true;
    public boolean showStatsSacrifices = true;
    public boolean showStatsBeaconSummary = true;
    public boolean showStatsDuration = true;
    public boolean showStatsMythicStats = true;

    // --- HUD display toggles: Dry Streak ---

    public boolean showDryChests = true;
    public boolean showDryPulls = true;

    // --- Validation ---

    /**
     * Clamps all numeric config fields to valid ranges.
     * Logs a warning for each value that is corrected.
     */
    public void validate() {
        tradeMarketBuyTaxPercent = clampInt("tradeMarketBuyTaxPercent",
                tradeMarketBuyTaxPercent, 0, 100);
        marketPriceStalenessHours = clampInt("marketPriceStalenessHours",
                marketPriceStalenessHours, 1, 8760);
        priceHistoryMaxDays = clampInt("priceHistoryMaxDays",
                priceHistoryMaxDays, 1, 365);
        crowdsourceFlushMinutes = clampInt("crowdsourceFlushMinutes",
                crowdsourceFlushMinutes, 1, 1440);
        tradeHistoryMinPriceFilter = clampLong("tradeHistoryMinPriceFilter",
                tradeHistoryMinPriceFilter, 0, Long.MAX_VALUE);

        // Null-guard string fields that should never be null after deserialization
        if (lastFilter == null) {
            lastFilter = "";
        }
        if (crowdsourceApiUrl == null) {
            crowdsourceApiUrl = "";
        }
    }

    private static int clampInt(String name, int value, int min, int max) {
        if (value < min) {
            WynnSortMod.logWarn("[WS:Config] {} value {} below minimum, clamped to {}", name, value, min);
            return min;
        }
        if (value > max) {
            WynnSortMod.logWarn("[WS:Config] {} value {} above maximum, clamped to {}", name, value, max);
            return max;
        }
        return value;
    }

    private static long clampLong(String name, long value, long min, long max) {
        if (value < min) {
            WynnSortMod.logWarn("[WS:Config] {} value {} below minimum, clamped to {}", name, value, min);
            return min;
        }
        if (value > max) {
            WynnSortMod.logWarn("[WS:Config] {} value {} above maximum, clamped to {}", name, value, max);
            return max;
        }
        return value;
    }

    // --- Defaults ---

    /**
     * Returns a fresh config instance with all fields set to their declared defaults.
     */
    public static WynnSortConfig resetToDefaults() {
        return new WynnSortConfig();
    }

    // --- Persistence ---

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(reader, WynnSortConfig.class);
                if (INSTANCE == null) {
                    INSTANCE = new WynnSortConfig();
                }
                INSTANCE.validate();
                WynnSortMod.log("[WS:Config] Loaded config from {}", CONFIG_PATH);
            } catch (JsonSyntaxException e) {
                WynnSortMod.logWarn("[WS:Config] Corrupt JSON in config file, resetting to defaults: {}",
                        e.getMessage());
                INSTANCE = new WynnSortConfig();
                save();
            } catch (IOException e) {
                WynnSortMod.logError("[WS:Config] Failed to load config, using defaults", e);
                INSTANCE = new WynnSortConfig();
            }
        } else {
            INSTANCE = new WynnSortConfig();
            save(); // Write defaults so the file exists for users to edit
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(INSTANCE, writer);
            }
            WynnSortMod.log("[WS:Config] Saved config to {}", CONFIG_PATH);
        } catch (IOException e) {
            WynnSortMod.logError("[WS:Config] Failed to save config", e);
        }
    }
}
