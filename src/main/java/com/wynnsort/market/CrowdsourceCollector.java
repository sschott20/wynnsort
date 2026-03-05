package com.wynnsort.market;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.ItemNameHelper;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.items.items.game.IngredientItem;
import com.wynntils.models.trademarket.type.TradeMarketPriceInfo;
import com.wynntils.models.trademarket.type.TradeMarketState;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects trade market price observations and feeds them into the
 * CrowdsourceQueue for periodic flushing to local storage / remote API.
 *
 * Subscribes to the same ContainerSetContentEvent.Post used by MarketPriceFeature,
 * but extracts richer metadata (rarity, item type, overall %, identified status)
 * for crowdsourced aggregation.
 *
 * Registered on the Wynntils event bus via WynntilsModMixin.
 */
public class CrowdsourceCollector {

    public static final CrowdsourceCollector INSTANCE = new CrowdsourceCollector();

    private static final String MOD_VERSION = "1.0.0";

    /** Base delay for exponential backoff on flush failures (in milliseconds). */
    private static final long BACKOFF_BASE_DELAY_MS = 30_000L; // 30 seconds
    /** Maximum backoff delay between flush retries (in milliseconds). */
    private static final long BACKOFF_MAX_DELAY_MS = 600_000L; // 10 minutes
    /** Number of consecutive failures before permanently disabling flush. */
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    private ScheduledExecutorService flushExecutor;

    /** Consecutive flush failure count for exponential backoff. */
    private volatile int flushFailureCount;
    /** Earliest time (epoch ms) at which the next flush attempt is allowed. */
    private volatile long nextAllowedFlushTime;
    /** Set to true after MAX_CONSECUTIVE_FAILURES, permanently disabling flush. */
    private volatile boolean flushPermanentlyDisabled;

    private CrowdsourceCollector() {}

    /**
     * Initializes the scheduled flusher and shutdown hook.
     * Called from WynnSortMod.onInitializeClient().
     */
    public void init() {
        CrowdsourceClient.INSTANCE.init();

        int flushMinutes = WynnSortConfig.INSTANCE.crowdsourceFlushMinutes;
        if (flushMinutes < 1) flushMinutes = 5;

        flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WynnSort-CrowdsourceFlush");
            t.setDaemon(true);
            return t;
        });

        flushExecutor.scheduleWithFixedDelay(
                this::flushQueue,
                flushMinutes,
                flushMinutes,
                TimeUnit.MINUTES
        );

        // Shutdown hook to flush remaining data
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WynnSortMod.log("[WS:Crowd] Shutdown hook — flushing remaining data");
            flushQueue();
            CrowdsourceClient.INSTANCE.shutdown();
        }, "WynnSort-CrowdsourceShutdown"));

        WynnSortMod.log("[WS:Crowd] Collector initialized (flush every {} min)", flushMinutes);
    }

    @SubscribeEvent
    public void onContainerContent(ContainerSetContentEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.crowdsourceEnabled) return;

        TradeMarketState state;
        try {
            state = Models.TradeMarket.getTradeMarketState();
        } catch (Exception e) {
            return;
        }

        // Only collect from search result screens
        if (state != TradeMarketState.DEFAULT_RESULTS && state != TradeMarketState.FILTERED_RESULTS) {
            return;
        }

        List<ItemStack> items = event.getItems();
        if (items == null) return;

        long now = System.currentTimeMillis();
        int collected = 0;

        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;

            try {
                CrowdsourceEntry entry = extractEntry(stack, now);
                if (entry != null) {
                    if (CrowdsourceQueue.INSTANCE.add(entry)) {
                        collected++;
                    }
                }
            } catch (Exception e) {
                WynnSortMod.logWarn("[WS:Crowd] Error extracting entry from '{}'",
                        stack.getHoverName().getString());
            }
        }

        if (collected > 0) {
            WynnSortMod.log("[WS:Crowd] Collected {} new entries (queue size: {})",
                    collected, CrowdsourceQueue.INSTANCE.size());
        }
    }

    /**
     * Extracts a CrowdsourceEntry from an ItemStack on the trade market.
     * Returns null if the item cannot be processed.
     */
    private CrowdsourceEntry extractEntry(ItemStack stack, long timestamp) {
        // Get base item name
        String baseName = ItemNameHelper.extractBaseName(stack);
        if (baseName == null) return null;

        // Get price via Wynntils
        TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(stack);
        if (priceInfo == null || priceInfo == TradeMarketPriceInfo.EMPTY || priceInfo.price() <= 0) {
            return null;
        }

        // Get WynnItem for type/rarity/identification info
        Optional<WynnItem> wynnOpt = Models.Item.getWynnItem(stack);
        if (wynnOpt.isEmpty()) return null;

        WynnItem wynnItem = wynnOpt.get();
        String itemType = wynnItem.getClass().getSimpleName();
        String rarity = "";
        boolean identified = false;
        float overallPct = -1.0f;
        int quantity = priceInfo.amount() > 0 ? priceInfo.amount() : 1;

        // Extract gear-specific info
        if (wynnItem instanceof GearItem gearItem) {
            try {
                rarity = gearItem.getItemInfo().tier().name();
            } catch (Exception ignored) {}

            Optional<GearInstance> instanceOpt = gearItem.getItemInstance();
            if (instanceOpt.isPresent()) {
                identified = true;
                try {
                    overallPct = instanceOpt.get().getOverallPercentage();
                } catch (Exception ignored) {}
            }
        }

        // Extract rarity for other item types if possible
        if (rarity.isEmpty()) {
            try {
                // For ingredients and other items, try to extract rarity from the hover name color
                String hoverName = stack.getHoverName().getString();
                if (hoverName != null) {
                    rarity = inferRarityFromType(wynnItem);
                }
            } catch (Exception ignored) {}
        }

        return new CrowdsourceEntry(
                baseName,
                itemType,
                rarity,
                priceInfo.price(),
                quantity,
                identified,
                overallPct,
                timestamp,
                MOD_VERSION
        );
    }

    /**
     * Attempts to infer rarity string from item type.
     * Returns empty string if unable to determine.
     */
    private String inferRarityFromType(WynnItem item) {
        try {
            if (item instanceof GearItem gearItem) {
                return gearItem.getItemInfo().tier().name();
            }
            // For ingredient items, Wynntils stores tier info differently
            if (item instanceof IngredientItem ingredientItem) {
                return "Tier" + ingredientItem.getIngredientInfo().tier();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Drains the queue and submits entries to storage.
     * Uses exponential backoff on consecutive failures, permanently disabling
     * after {@link #MAX_CONSECUTIVE_FAILURES} consecutive errors.
     */
    private void flushQueue() {
        if (flushPermanentlyDisabled) return;

        // Respect backoff delay
        long now = System.currentTimeMillis();
        if (now < nextAllowedFlushTime) {
            WynnSortMod.log("[WS:Crowd] Flush deferred — backoff active (failures: {}, next attempt in {}s)",
                    flushFailureCount, (nextAllowedFlushTime - now) / 1000);
            return;
        }

        try {
            List<CrowdsourceEntry> entries = CrowdsourceQueue.INSTANCE.drain();
            if (entries.isEmpty()) return;

            WynnSortMod.log("[WS:Crowd] Flushing {} entries", entries.size());
            CrowdsourceClient.INSTANCE.submitBatch(entries);

            // Success — reset backoff state
            if (flushFailureCount > 0) {
                WynnSortMod.log("[WS:Crowd] Flush succeeded after {} previous failure(s), resetting backoff",
                        flushFailureCount);
            }
            flushFailureCount = 0;
            nextAllowedFlushTime = 0;
        } catch (Exception e) {
            flushFailureCount++;

            if (flushFailureCount >= MAX_CONSECUTIVE_FAILURES) {
                flushPermanentlyDisabled = true;
                WynnSortMod.logError("[WS:Crowd] Flush permanently disabled after {} consecutive failures",
                        MAX_CONSECUTIVE_FAILURES);
            } else {
                long backoffMs = Math.min(
                        (long) Math.pow(2, flushFailureCount) * BACKOFF_BASE_DELAY_MS,
                        BACKOFF_MAX_DELAY_MS
                );
                nextAllowedFlushTime = System.currentTimeMillis() + backoffMs;
                WynnSortMod.logWarn("[WS:Crowd] Flush failed (attempt {}/{}), next retry in {}s: {}",
                        flushFailureCount, MAX_CONSECUTIVE_FAILURES, backoffMs / 1000, e.getMessage());
            }
        }
    }
}
