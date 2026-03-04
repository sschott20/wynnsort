package com.wynnsort.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
    public boolean autoSortCheapest = true;
    /** true = use Nori/Wynnpool weighted scale, false = use default overall % */
    public boolean useWeightedScale = true;
    /** Show orange beacon duration tracker during lootruns */
    public boolean lootrunHudEnabled = true;
    /** Log trade market transactions to disk */
    public boolean tradeHistoryEnabled = true;
    /** Minimum price filter for trade history display (emeralds) */
    public long tradeHistoryMinPriceFilter = 5000;
    /** Trade market buyer tax percentage (applied to BUY display prices) */
    public int tradeMarketBuyTaxPercent = 3;
    /** Verbose trade market logging (logs every state change, container, slot, chat) */
    public boolean tradeMarketLogging = true;
    /** Enable market price caching and tooltip display */
    public boolean marketPriceCacheEnabled = true;
    /** Hours before a cached market price is considered stale */
    public int marketPriceStalenessHours = 168;

    // --- Persistence ---

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(reader, WynnSortConfig.class);
                if (INSTANCE == null) {
                    INSTANCE = new WynnSortConfig();
                }
                WynnSortMod.log("Loaded config from {}", CONFIG_PATH);
            } catch (IOException e) {
                WynnSortMod.logError("Failed to load config, using defaults", e);
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
            WynnSortMod.log("Saved config to {}", CONFIG_PATH);
        } catch (IOException e) {
            WynnSortMod.logError("Failed to save config", e);
        }
    }
}
