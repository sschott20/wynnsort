package com.wynnsort.feature;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.SortState;
import com.wynnsort.util.ItemNameHelper;
import com.wynnsort.history.TransactionRecord;
import com.wynnsort.history.TransactionStore;
import com.wynntils.handlers.chat.event.ChatMessageEvent;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.mc.event.ScreenOpenedEvent;
import com.wynntils.mc.event.ScreenClosedEvent;
import com.wynntils.core.components.Models;
import com.wynntils.models.containers.Container;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.*;
import com.wynntils.models.stats.type.StatActualValue;
import com.wynntils.models.trademarket.type.TradeMarketPriceInfo;
import com.wynntils.models.trademarket.type.TradeMarketState;
import com.wynntils.models.trademarket.event.TradeMarketStateEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.PersistentLog;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Comprehensive trade market observer. Has two responsibilities:
 *
 * 1. LOGGING — structured, permanent log entries prefixed [WS:TM] that record
 *    every state transition, container snapshot, slot update, chat message,
 *    and Wynntils API value while the trade market is active.
 *
 * 2. TRANSACTION CAPTURE — detects buy/sell completions and writes them to
 *    TransactionStore for the history screen.
 */
public class TradeMarketLogger {

    public static final TradeMarketLogger INSTANCE = new TradeMarketLogger();

    // Chat patterns for transaction detection
    private static final Pattern CLAIM_ITEMS_PATTERN = Pattern.compile(
            "Visit the Trade Market to claim your items");
    private static final Pattern CLAIM_EMERALDS_PATTERN = Pattern.compile(
            "Visit the Trade Market to claim your emeralds");
    // "Finished buying/selling [item]." — fires right before claim messages
    private static final Pattern FINISHED_BUYING_PATTERN = Pattern.compile(
            "Finished buying (.+?)\\.");
    private static final Pattern FINISHED_SELLING_PATTERN = Pattern.compile(
            "Finished selling (.+?)\\.");

    // Price patterns for lore parsing (fallback when Wynntils API fails)
    private static final Pattern LORE_PRICE_PATTERN = Pattern.compile(
            "(?:Price|Total|Cost):\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LORE_EMERALD_PATTERN = Pattern.compile(
            "([\\d,]+)\\s*emeralds?", Pattern.CASE_INSENSITIVE);
    // Wynntils denomination format: Xstx Yle Zeb We
    private static final Pattern DENOM_PATTERN = Pattern.compile(
            "(\\d+)\\s*(stx|le|eb|e)\\b", Pattern.CASE_INSENSITIVE);
    // VIEWING_ORDER price format: "0 x 810,024²" — quantity x unit_price (² = emerald symbol)
    private static final Pattern ORDER_PRICE_PATTERN = Pattern.compile(
            "(\\d+)\\s+x\\s+([\\d,]+)");

    private static final long STX_MULT = 262144L;
    private static final long LE_MULT = 4096L;
    private static final long EB_MULT = 64L;

    // Tracked state
    private TradeMarketState lastKnownState = TradeMarketState.NOT_ACTIVE;

    // Pending purchase context (captured during BUYING state)
    private String pendingItemName = null;
    private long pendingPrice = -1;

    // Pending sell contexts — one per listing, keyed by lowercase base name.
    // Sells are asynchronous (item sells after player leaves trade market),
    // so we must track each listing separately.
    private record PendingSellContext(String itemName, String baseName, String fingerprint, long price) {}
    private final LinkedHashMap<String, PendingSellContext> pendingSells = new LinkedHashMap<>();
    // Tracks the base name of the item currently being listed (for slot-update price capture)
    private String currentSellBaseName = null;

    // Item name from "Finished buying/selling [item]" chat message
    // More reliable than pending context since it comes from the server
    private String lastFinishedItemName = null;

    // Pending fingerprint data for buy/sell context
    private String pendingBaseName = null;
    private String pendingFingerprint = null;

    // ── Async fulfilled order tracking ──
    // When an order fulfills while the player is offline, there are no chat messages.
    // Instead: VIEWING_TRADES shows "Fulfilled - Sold/Bought X/Y items", and clicking
    // the order goes to VIEWING_ORDER where slot 52 is "Withdraw Items".
    // Clicking withdraw changes slot 52 to "Closing your order..." — that's our commit signal.

    /** Cached transaction type from VIEWING_TRADES lore ("Fulfilled - Sold/Bought"). Keyed by lowercase item name. */
    private final Map<String, TransactionRecord.Type> fulfilledOrderTypes = new HashMap<>();

    /** Context for an async withdrawal detected in VIEWING_ORDER. */
    private record PendingWithdrawal(String itemName, String baseName, String fingerprint,
                                     long price, TransactionRecord.Type type) {}
    private PendingWithdrawal pendingWithdrawal = null;

    /** Tracks sells committed via commitSell() so commitWithdrawal() can skip duplicates.
     *  Key: lowercase baseName, Value: count of uncommitted chat-committed sells. */
    private final Map<String, Integer> chatCommittedSells = new LinkedHashMap<>();
    private final Map<String, Integer> chatCommittedBuys = new LinkedHashMap<>();

    /** Hash of last logged container contents, used to deduplicate container dumps. */
    private String lastContainerHash = null;

    // Sell screen overlay: matched buy price to show when selling
    private static volatile TransactionRecord matchedBuyRecord = null;

    private TradeMarketLogger() {}

    private static void tmLog(String msg, Object... args) {
        PersistentLog.info("[WS:TM] " + msg, args);
    }

    private static void tmWarn(String msg, Object... args) {
        PersistentLog.warn("[WS:TM] " + msg, args);
    }

    /** Returns the matched buy record for the current sell screen, or null. */
    public static TransactionRecord getMatchedBuyRecord() {
        return matchedBuyRecord;
    }

    // ────────────────────────────────────────────────────────────────────
    //  STATE TRANSITIONS
    // ────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onTradeMarketState(TradeMarketStateEvent event) {
        TradeMarketState oldState = event.getOldState();
        TradeMarketState newState = event.getNewState();

        if (WynnSortConfig.INSTANCE.tradeMarketLogging) {
            tmLog("State change: {} -> {}", oldState, newState);
            logApiSnapshot("state-change");
        }

        DiagnosticLog.event(DiagnosticLog.Category.TRADE_MARKET, "state_change",
                Map.of("from", String.valueOf(oldState), "to", String.valueOf(newState)));

        // Clear sell overlay and current-sell tracker when leaving SELLING state
        if (oldState == TradeMarketState.SELLING && newState != TradeMarketState.SELLING) {
            matchedBuyRecord = null;
            // Don't remove from pendingSells here — sells are async, and commitSell()
            // needs the context when the "Finished selling" chat message arrives later.
            currentSellBaseName = null;
        }

        // Clear pending withdrawal when leaving VIEWING_ORDER
        if (oldState == TradeMarketState.VIEWING_ORDER && newState != TradeMarketState.VIEWING_ORDER) {
            pendingWithdrawal = null;
        }

        // Reset default sort flag and clear transient state when trade market closes.
        // Do NOT clear pendingSells — sells are async and may complete hours/days later;
        // the map is bounded to 20 entries and cleaned up when sells are committed.
        if (newState == TradeMarketState.NOT_ACTIVE) {
            SortState.resetDefaultSort();
            fulfilledOrderTypes.clear();
        }

        lastKnownState = newState;
    }

    // ────────────────────────────────────────────────────────────────────
    //  SCREEN EVENTS
    // ────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onScreenOpen(ScreenOpenedEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.tradeMarketLogging) return;
        if (!isTradeMarketRelevant()) return;

        Screen screen = event.getScreen();
        String screenClass = screen != null ? screen.getClass().getSimpleName() : "null";
        String screenTitle = screen != null ? screen.getTitle().getString() : "null";

        tmLog("Screen opened: class={}, title=\"{}\"", screenClass, screenTitle);
        logContainerInfo("screen-open");
    }

    @SubscribeEvent
    public void onScreenClose(ScreenClosedEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.tradeMarketLogging) return;
        if (lastKnownState != TradeMarketState.NOT_ACTIVE) {
            tmLog("Screen closed (was in state: {})", lastKnownState);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  CONTAINER CONTENT — full snapshot when container loads
    // ────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onContainerContent(ContainerSetContentEvent.Post event) {
        TradeMarketState state = safeGetState();

        if (WynnSortConfig.INSTANCE.tradeMarketLogging && state != TradeMarketState.NOT_ACTIVE) {
            logContainerContent(event, state);
        }

        // Transaction capture: during BUYING, SELLING, VIEWING_TRADES, or VIEWING_ORDER
        if (WynnSortConfig.INSTANCE.tradeHistoryEnabled) {
            if (state == TradeMarketState.BUYING) {
                captureBuyContext(event.getItems());
            } else if (state == TradeMarketState.SELLING) {
                captureSellContext(event.getItems());
            } else if (state == TradeMarketState.VIEWING_TRADES) {
                scanFulfilledOrders(event.getItems());
            } else if (state == TradeMarketState.VIEWING_ORDER) {
                captureWithdrawalContext(event.getItems());
            }
        }
    }

    private void logContainerContent(ContainerSetContentEvent.Post event, TradeMarketState state) {
        List<ItemStack> items = event.getItems();
        if (items == null) return;

        // Deduplication: build a hash of non-empty slot names and skip if unchanged
        StringBuilder hashBuilder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack != null && !stack.isEmpty()) {
                try {
                    hashBuilder.append(i).append(':').append(stack.getHoverName().getString()).append(';');
                } catch (Exception ignored) {
                    hashBuilder.append(i).append(":?;");
                }
            }
        }
        String contentHash = hashBuilder.toString();
        if (contentHash.equals(lastContainerHash)) {
            return; // Unchanged — skip silently
        }
        lastContainerHash = contentHash;

        logContainerInfo("content-update");

        tmLog("Container content: containerId={}, stateId={}, {} slots, state={}",
                event.getContainerId(), event.getStateId(), items.size(), state);

        // Per-slot details only in verbose mode to avoid log spam
        if (WynnSortConfig.INSTANCE.tradeMarketVerboseLogging) {
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack == null || stack.isEmpty()) continue;
                logSlotItem(i, stack);
            }
            logApiSnapshot("content-update");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  SLOT UPDATES — individual slot changes
    // ────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onSlotUpdate(ContainerSetSlotEvent.Post event) {
        TradeMarketState state = safeGetState();
        if (!isTradeMarketRelevant()) return;

        ItemStack stack = event.getItemStack();
        int slot = event.getSlot();
        int containerId = event.getContainerId();

        if (WynnSortConfig.INSTANCE.tradeMarketVerboseLogging) {
            if (stack == null || stack.isEmpty()) {
                tmLog("Slot cleared: container={}, slot={}", containerId, slot);
            } else {
                tmLog("Slot updated: container={}, slot={}", containerId, slot);
                logSlotItem(slot, stack);
            }
        }

        // Also try to capture price from slot updates during BUYING or SELLING
        if (WynnSortConfig.INSTANCE.tradeHistoryEnabled
                && stack != null && !stack.isEmpty()) {
            if (state == TradeMarketState.BUYING && pendingPrice < 0) {
                long price = extractPriceFromStack(stack);
                if (price > 0) {
                    pendingPrice = price;
                    tmLog("Buy price captured from slot update {}: {}", slot, price);
                }
            } else if (state == TradeMarketState.SELLING && currentSellBaseName != null) {
                String key = currentSellBaseName.toLowerCase();
                PendingSellContext existing = pendingSells.get(key);
                if (existing != null && existing.price < 0) {
                    long price = extractPriceFromStack(stack);
                    if (price > 0) {
                        pendingSells.put(key, new PendingSellContext(
                                existing.itemName, existing.baseName, existing.fingerprint, price));
                        tmLog("Sell price captured from slot update {}: {}", slot, price);
                    }
                }
            } else if (state == TradeMarketState.VIEWING_TRADES) {
                // Scan individual slot updates for fulfilled order info.
                // The first VIEWING_TRADES content event often arrives with empty trade slots;
                // the actual items come in via slot updates. We must scan them here too.
                scanSingleSlotForFulfilled(slot, stack);
            } else if (state == TradeMarketState.VIEWING_ORDER && slot == 52
                    && pendingWithdrawal != null) {
                // Detect "Closing your order..." on slot 52 — means the player clicked withdraw
                String name = stack.getHoverName().getString();
                String cleanName = ItemNameHelper.cleanItemName(name);
                if (cleanName != null && cleanName.contains("Closing your order")) {
                    tmLog(">>> Async withdrawal confirmed (slot 52 = 'Closing your order...')");
                    commitWithdrawal();
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  CHAT MESSAGES — log + detect transactions
    // ────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onChatMessage(ChatMessageEvent.Match event) {
        String msg;
        try {
            msg = event.getMessage().getString();
        } catch (Exception e) {
            return;
        }
        if (msg == null || msg.isEmpty()) return;

        String msgType = "unknown";
        try { msgType = event.getMessageType().toString(); } catch (Exception ignored) {}

        if (WynnSortConfig.INSTANCE.tradeMarketVerboseLogging && isTradeMarketRelevant()) {
            tmLog("Chat [{}]: \"{}\"", msgType, msg);
        }

        if (!WynnSortConfig.INSTANCE.tradeHistoryEnabled) return;

        try {
            // "Finished buying/selling [item]." fires right before claim messages
            Matcher buyMatch = FINISHED_BUYING_PATTERN.matcher(msg);
            if (buyMatch.find()) {
                lastFinishedItemName = ItemNameHelper.cleanItemName(buyMatch.group(1));
                tmLog("Finished buying: \"{}\"", lastFinishedItemName);
            }
            Matcher sellMatch = FINISHED_SELLING_PATTERN.matcher(msg);
            if (sellMatch.find()) {
                lastFinishedItemName = ItemNameHelper.cleanItemName(sellMatch.group(1));
                tmLog("Finished selling: \"{}\"", lastFinishedItemName);
            }

            if (CLAIM_ITEMS_PATTERN.matcher(msg).find()) {
                tmLog(">>> BUY confirmation detected");
                logApiSnapshot("buy-confirm");
                commitBuy();
            } else if (CLAIM_EMERALDS_PATTERN.matcher(msg).find()) {
                tmLog(">>> SELL confirmation detected");
                logApiSnapshot("sell-confirm");
                commitSell();
            }
        } catch (Exception e) {
            tmWarn("Error processing trade message", e);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  LOGGING HELPERS
    // ────────────────────────────────────────────────────────────────────

    private void logSlotItem(int slot, ItemStack stack) {
        String hoverName = "?";
        try { hoverName = stack.getHoverName().getString(); } catch (Exception ignored) {}

        String wynnType = "none";
        String wynnDetail = "";
        try {
            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isPresent()) {
                WynnItem item = opt.get();
                wynnType = item.getClass().getSimpleName();

                if (item instanceof GearItem gi) {
                    try {
                        wynnDetail = " itemInfo=" + gi.getItemInfo().name();
                        if (gi.getItemInstance().isPresent()) {
                            GearInstance inst = gi.getItemInstance().get();
                            if (inst.identifications().isEmpty()) {
                                wynnDetail += " overall=N/A";
                            } else {
                                wynnDetail += " overall=" + inst.getOverallPercentage() + "%";
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // Price info from Wynntils
        String priceStr = "";
        try {
            TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(stack);
            if (priceInfo != null && priceInfo != TradeMarketPriceInfo.EMPTY) {
                priceStr = String.format(" price=%d amount=%d total=%d silverbull=%d",
                        priceInfo.price(), priceInfo.amount(),
                        priceInfo.totalPrice(), priceInfo.silverbullPrice());
            }
        } catch (Exception ignored) {}

        if (WynnSortConfig.INSTANCE.tradeMarketVerboseLogging) {
            // Verbose mode: include full lore text
            StringBuilder lore = new StringBuilder();
            try {
                Minecraft mc = Minecraft.getInstance();
                net.minecraft.world.item.Item.TooltipContext ctx;
                if (mc.level != null) {
                    ctx = net.minecraft.world.item.Item.TooltipContext.of(mc.level);
                } else {
                    ctx = net.minecraft.world.item.Item.TooltipContext.EMPTY;
                }
                var tooltipLines = stack.getTooltipLines(ctx, mc.player,
                        net.minecraft.world.item.TooltipFlag.NORMAL);
                if (tooltipLines != null) {
                    int lineCount = 0;
                    for (var line : tooltipLines) {
                        String text = line.getString();
                        if (text == null || text.isBlank()) continue;
                        if (lineCount++ >= 10) {
                            lore.append("\n            | ...(+" + (tooltipLines.size() - lineCount) + " more)");
                            break;
                        }
                        lore.append("\n            | ").append(text);
                    }
                }
            } catch (Exception e) {
                lore.append("\n            | [lore error: ").append(e.getClass().getSimpleName())
                        .append(": ").append(e.getMessage()).append("]");
            }

            tmLog("  slot[{}]: name=\"{}\" wynnType={}{}{}{}", slot, hoverName,
                    wynnType, wynnDetail, priceStr,
                    lore.length() > 0 ? "\n            lore:" + lore : "");
        } else {
            // Compact mode: one-line summary
            tmLog("  slot[{}]: name=\"{}\" wynnType={}{}{}", slot, hoverName,
                    wynnType, wynnDetail, priceStr);
        }
    }

    private void logContainerInfo(String trigger) {
        try {
            Container container = Models.Container.getCurrentContainer();
            if (container != null) {
                tmLog("Container [{}]: class={}, name=\"{}\", id={}",
                        trigger,
                        container.getClass().getSimpleName(),
                        container.getContainerName(),
                        container.getContainerId());
            } else {
                tmLog("Container [{}]: null", trigger);
            }
        } catch (Exception e) {
            tmLog("Container [{}]: error reading - {}", trigger, e.getMessage());
        }
    }

    private void logApiSnapshot(String trigger) {
        try {
            TradeMarketState state = Models.TradeMarket.getTradeMarketState();
            boolean inTM = Models.TradeMarket.inTradeMarket();
            boolean inChat = Models.TradeMarket.inChatInput();

            tmLog("API [{}]: state={}, inTradeMarket={}, inChatInput={}",
                    trigger, state, inTM, inChat);

            // Detailed API queries only in verbose mode
            if (WynnSortConfig.INSTANCE.tradeMarketVerboseLogging) {
                try {
                    int unitPrice = Models.TradeMarket.getUnitPrice();
                    tmLog("API [{}]: unitPrice={}", trigger, unitPrice);
                } catch (Exception e) {
                    tmLog("API [{}]: unitPrice=error({})", trigger, e.getMessage());
                }

                try {
                    String soldName = Models.TradeMarket.getSoldItemName();
                    tmLog("API [{}]: soldItemName=\"{}\"", trigger, soldName);
                } catch (Exception e) {
                    tmLog("API [{}]: soldItemName=error({})", trigger, e.getMessage());
                }

                try {
                    var pci = Models.TradeMarket.getPriceCheckInfo();
                    if (pci != null) {
                        tmLog("API [{}]: priceCheck: recommended={}, bid={}, ask={}",
                                trigger, pci.recommendedPrice(), pci.bid(), pci.ask());
                    }
                } catch (Exception e) {
                    tmLog("API [{}]: priceCheck=error({})", trigger, e.getMessage());
                }

                try {
                    String filter = Models.TradeMarket.getLastSearchFilter();
                    if (filter != null && !filter.isEmpty()) {
                        tmLog("API [{}]: lastSearchFilter=\"{}\"", trigger, filter);
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            tmWarn("API snapshot failed [{}]", trigger, e);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  TRANSACTION CAPTURE
    // ────────────────────────────────────────────────────────────────────

    private void captureBuyContext(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;

        try {
            String foundItem = null;
            long foundPrice = -1;
            String foundBase = null;
            String foundFingerprint = null;

            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack == null || stack.isEmpty()) continue;

                // Find the item name and fingerprint (first game item that's not an emerald)
                if (foundItem == null) {
                    foundBase = ItemNameHelper.extractBaseName(stack);
                    if (foundBase != null) {
                        foundItem = foundBase;
                        foundFingerprint = buildStatFingerprint(stack);
                    }
                }

                // Try Wynntils API for price first
                if (foundPrice < 0) {
                    try {
                        TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(stack);
                        if (priceInfo != null && priceInfo != TradeMarketPriceInfo.EMPTY && priceInfo.totalPrice() > 0) {
                            foundPrice = priceInfo.totalPrice();
                            tmLog("Price from Wynntils API (slot {}): {}", i, foundPrice);
                        }
                    } catch (Exception ignored) {}
                }

                // Fallback: parse price from item lore
                if (foundPrice < 0) {
                    foundPrice = extractPriceFromStack(stack);
                    if (foundPrice > 0) {
                        tmLog("Price from lore parsing (slot {}): {}", i, foundPrice);
                    }
                }
            }

            if (foundItem != null) {
                pendingItemName = ItemNameHelper.cleanItemName(foundItem);
                pendingBaseName = foundBase;
                pendingFingerprint = foundFingerprint;
                if (foundPrice > 0) {
                    pendingPrice = foundPrice;
                }
                tmLog("Pending buy captured: item=\"{}\", base=\"{}\", fingerprint={}, price={}",
                        pendingItemName, pendingBaseName,
                        foundFingerprint != null ? "yes(" + foundFingerprint.length() + "chars)" : "null",
                        pendingPrice);
            }
        } catch (Exception e) {
            tmWarn("Error capturing buy context", e);
        }
    }

    private void captureSellContext(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;

        try {
            String foundItem = null;
            long foundPrice = -1;
            String foundBase = null;
            String foundFingerprint = null;

            // Pass 1: Find item name and fingerprint
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack == null || stack.isEmpty()) continue;

                if (foundItem == null) {
                    foundBase = ItemNameHelper.extractBaseName(stack);
                    if (foundBase != null) {
                        foundItem = foundBase;
                        foundFingerprint = buildStatFingerprint(stack);
                    }
                }
            }

            // Pass 2: Extract price with prioritized patterns.
            // Don't use calculateItemPriceInfo — it's for search result items, not the sell screen.
            // Scan explicit "Price:/Total:/Cost:" patterns first across ALL slots,
            // then "X emeralds", then denomination last — to avoid picking up stray
            // denomination text (e.g., "1stx" from balance/fee display items).
            foundPrice = extractPrioritizedPrice(items);

            if (foundBase != null) {
                String cleanName = foundItem != null ? ItemNameHelper.cleanItemName(foundItem) : foundBase;
                String key = foundBase.toLowerCase();
                pendingSells.put(key, new PendingSellContext(
                        cleanName, foundBase, foundFingerprint,
                        foundPrice > 0 ? foundPrice : -1));
                currentSellBaseName = foundBase;

                // Keep map bounded (oldest entries evicted first)
                while (pendingSells.size() > 20) {
                    var it = pendingSells.entrySet().iterator();
                    it.next();
                    it.remove();
                }

                // Look up matching buy record for the sell overlay
                TransactionRecord match = TransactionStore.findMatchingBuy(foundBase, foundFingerprint);
                matchedBuyRecord = match;
                if (match != null) {
                    tmLog("Sell overlay: matched buy for \"{}\" at price {}",
                            match.itemName, match.priceEmeralds);
                } else {
                    tmLog("Sell overlay: no matching buy found for \"{}\"", foundBase);
                }

                tmLog("Pending sell captured: item=\"{}\", base=\"{}\", fingerprint={}, price={}",
                        cleanName, foundBase,
                        foundFingerprint != null ? "yes(" + foundFingerprint.length() + "chars)" : "null",
                        foundPrice);
            }
        } catch (Exception e) {
            tmWarn("Error capturing sell context", e);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  ASYNC FULFILLED ORDER DETECTION
    // ────────────────────────────────────────────────────────────────────

    /**
     * Scan trade slots in VIEWING_TRADES for "Fulfilled - Sold X/Y items" or
     * "Fulfilled - Bought X/Y items" lore. Caches the transaction type by item name
     * so we know whether a subsequent VIEWING_ORDER is a buy or sell withdrawal.
     */
    private void scanFulfilledOrders(List<ItemStack> items) {
        if (items == null) return;
        // Don't clear — slot updates may have already populated entries via scanSingleSlotForFulfilled.
        // Content events will re-add or overwrite entries, which is fine.

        try {
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack == null || stack.isEmpty()) continue;

                List<String> loreLines = getPlainLoreLines(stack);
                for (String line : loreLines) {
                    if (line.contains("Fulfilled")) {
                        // Extract the item name from the stack's hover name
                        String itemName = ItemNameHelper.cleanItemName(stack.getHoverName().getString());
                        if (itemName == null || itemName.isEmpty()) break;
                        // Also try Wynntils base name
                        String baseName = ItemNameHelper.extractBaseName(stack);
                        String key = (baseName != null ? baseName : itemName).toLowerCase();

                        if (line.contains("Sold")) {
                            fulfilledOrderTypes.put(key, TransactionRecord.Type.SELL);
                            tmLog("Fulfilled SELL detected at slot {} for \"{}\": \"{}\"", i, key, line);
                        } else if (line.contains("Bought")) {
                            fulfilledOrderTypes.put(key, TransactionRecord.Type.BUY);
                            tmLog("Fulfilled BUY detected at slot {} for \"{}\": \"{}\"", i, key, line);
                        }
                        break;
                    }
                }
            }

            if (!fulfilledOrderTypes.isEmpty()) {
                tmLog("Fulfilled orders found: {}", fulfilledOrderTypes.size());
            }
        } catch (Exception e) {
            tmWarn("Error scanning fulfilled orders: {}", e.getMessage());
        }
    }

    /**
     * Scan a single slot update in VIEWING_TRADES for fulfilled order info.
     * Complements scanFulfilledOrders: the initial content event may arrive with
     * empty trade slots, and the actual items come in via individual slot updates.
     */
    private void scanSingleSlotForFulfilled(int slot, ItemStack stack) {
        try {
            List<String> loreLines = getPlainLoreLines(stack);
            for (String line : loreLines) {
                if (line.contains("Fulfilled")) {
                    String itemName = ItemNameHelper.cleanItemName(stack.getHoverName().getString());
                    if (itemName == null || itemName.isEmpty()) return;
                    String baseName = ItemNameHelper.extractBaseName(stack);
                    String key = (baseName != null ? baseName : itemName).toLowerCase();

                    if (line.contains("Sold")) {
                        fulfilledOrderTypes.put(key, TransactionRecord.Type.SELL);
                        tmLog("Fulfilled SELL detected from slot update {} for \"{}\": \"{}\"", slot, key, line);
                    } else if (line.contains("Bought")) {
                        fulfilledOrderTypes.put(key, TransactionRecord.Type.BUY);
                        tmLog("Fulfilled BUY detected from slot update {} for \"{}\": \"{}\"", slot, key, line);
                    }
                    return;
                }
            }
        } catch (Exception e) {
            tmWarn("Error scanning slot {} for fulfilled: {}", slot, e.getMessage());
        }
    }

    /**
     * In VIEWING_ORDER, capture item details from slot 28 and check slot 52 for
     * "Withdraw Items" / "Your order has been fulfilled". Builds a PendingWithdrawal
     * so we can commit it when slot 52 changes to "Closing your order...".
     */
    private void captureWithdrawalContext(List<ItemStack> items) {
        if (items == null || items.size() <= 52) return;

        try {
            // Check slot 52 for "Withdraw Items" with "Your order has been fulfilled"
            ItemStack actionSlot = items.get(52);
            if (actionSlot == null || actionSlot.isEmpty()) return;

            String actionName = ItemNameHelper.cleanItemName(actionSlot.getHoverName().getString());
            if (actionName == null || !actionName.contains("Withdraw Items")) return;

            // Verify the "fulfilled" lore
            List<String> actionLore = getPlainLoreLines(actionSlot);
            boolean isFulfilled = false;
            for (String line : actionLore) {
                if (line.contains("Your order has been fulfilled")) {
                    isFulfilled = true;
                    break;
                }
            }
            if (!isFulfilled) return;

            // Capture item from slot 28
            ItemStack itemSlot = items.get(28);
            if (itemSlot == null || itemSlot.isEmpty()) {
                tmWarn("Async withdrawal: slot 28 is empty, cannot capture item");
                return;
            }

            String baseName = ItemNameHelper.extractBaseName(itemSlot);
            String itemName = baseName;
            if (itemName == null) {
                // Fallback: use hover name
                itemName = ItemNameHelper.cleanItemName(itemSlot.getHoverName().getString());
            }
            if (itemName == null || itemName.isEmpty()) {
                tmWarn("Async withdrawal: could not determine item name from slot 28");
                return;
            }

            String fingerprint = buildStatFingerprint(itemSlot);

            // Extract price from VIEWING_ORDER screen.
            // Strategy 1: Compute total from emerald item stacks in trade area (most reliable).
            // Strategy 2: Parse lore from slot 28 (order item) using ORDER_PRICE_PATTERN only.
            // Strategy 3: Parse lore from trade-area slots (0-53) only — never scan
            //   player inventory (54+) which has gear stats that produce false positives.
            long price = computeEmeraldTotal(items);
            if (price > 0) {
                tmLog("Order price from emerald stacks: {}", price);
            }
            if (price <= 0) {
                price = extractOrderPrice(itemSlot);
                if (price > 0) {
                    tmLog("Order price from slot 28 lore: {}", price);
                }
            }
            if (price <= 0) {
                for (int i = 0; i < Math.min(items.size(), 54); i++) {
                    if (i == 28 || i == 52) continue;
                    ItemStack s = items.get(i);
                    if (s == null || s.isEmpty()) continue;
                    price = extractOrderPrice(s);
                    if (price > 0) {
                        tmLog("Order price from trade-area slot {} lore: {}", i, price);
                        break;
                    }
                }
            }

            // Determine transaction type from our VIEWING_TRADES fulfilled order cache.
            // Match the item from slot 28 against the fulfilled order types we cached.
            TransactionRecord.Type txType = null;
            String lookupKey = (baseName != null ? baseName : itemName).toLowerCase();
            txType = fulfilledOrderTypes.get(lookupKey);

            // Fuzzy match if exact key didn't work
            if (txType == null) {
                for (var entry : fulfilledOrderTypes.entrySet()) {
                    if (containsIgnoreCase(lookupKey, entry.getKey())
                            || containsIgnoreCase(entry.getKey(), lookupKey)) {
                        txType = entry.getValue();
                        tmLog("Fulfilled type matched via fuzzy: \"{}\" ~ \"{}\" -> {}",
                                lookupKey, entry.getKey(), txType);
                        break;
                    }
                }
            }

            // If type still unknown, try to infer from context
            if (txType == null) {
                if (baseName != null && pendingSells.containsKey(baseName.toLowerCase())) {
                    txType = TransactionRecord.Type.SELL;
                    tmLog("Type inferred from pending sells: SELL for \"{}\"", baseName);
                } else {
                    // Infer from emerald stacks in trade area: if there are emeralds to
                    // withdraw alongside the item, this is a SELL (collecting proceeds).
                    // A BUY withdrawal typically only has the item itself.
                    boolean hasEmeralds = false;
                    for (int i = 0; i < Math.min(items.size(), 54); i++) {
                        if (i == 28 || i == 46 || i == 52) continue;
                        ItemStack s = items.get(i);
                        if (s == null || s.isEmpty()) continue;
                        try {
                            Optional<WynnItem> opt = Models.Item.getWynnItem(s);
                            if (opt.isPresent() && opt.get() instanceof EmeraldItem) {
                                hasEmeralds = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (hasEmeralds) {
                        txType = TransactionRecord.Type.SELL;
                        tmLog("Type inferred from emerald stacks in trade area: SELL for \"{}\"", itemName);
                    } else {
                        txType = TransactionRecord.Type.BUY;
                        tmWarn("Async withdrawal: could not determine type for \"{}\", defaulting to BUY (fulfilledOrderTypes keys={})",
                                itemName, fulfilledOrderTypes.keySet());
                    }
                }
            }

            pendingWithdrawal = new PendingWithdrawal(itemName, baseName, fingerprint, price, txType);
            tmLog("Pending withdrawal captured: type={}, item=\"{}\", base=\"{}\", fingerprint={}, price={}",
                    txType, itemName, baseName,
                    fingerprint != null ? "yes(" + fingerprint.length() + "chars)" : "null",
                    price);

        } catch (Exception e) {
            tmWarn("Error capturing withdrawal context: {}", e.getMessage());
        }
    }

    /**
     * Commit a pending async withdrawal as a transaction (BUY or SELL).
     * Called when slot 52 changes to "Closing your order..." in VIEWING_ORDER.
     */
    private void commitWithdrawal() {
        if (pendingWithdrawal == null) {
            tmWarn("commitWithdrawal called but no pending withdrawal");
            return;
        }

        PendingWithdrawal w = pendingWithdrawal;
        pendingWithdrawal = null;

        // Skip if this transaction was already committed via chat message
        if (w.baseName != null) {
            String key = w.baseName.toLowerCase();
            if (w.type == TransactionRecord.Type.SELL) {
                Integer count = chatCommittedSells.get(key);
                if (count != null && count > 0) {
                    if (count == 1) chatCommittedSells.remove(key);
                    else chatCommittedSells.put(key, count - 1);
                    tmLog("Skipping withdrawal SELL for \"{}\" — already committed via chat", w.baseName);
                    pendingSells.remove(key);
                    return;
                }
            } else if (w.type == TransactionRecord.Type.BUY) {
                Integer count = chatCommittedBuys.get(key);
                if (count != null && count > 0) {
                    if (count == 1) chatCommittedBuys.remove(key);
                    else chatCommittedBuys.put(key, count - 1);
                    tmLog("Skipping withdrawal BUY for \"{}\" — already committed via chat", w.baseName);
                    return;
                }
            }
        }

        tmLog("Committing async {}: item=\"{}\", base=\"{}\", price={}",
                w.type, w.itemName, w.baseName, w.price);

        TransactionStore.addTransaction(new TransactionRecord(
                w.itemName, w.price > 0 ? w.price : 0, w.type, "", 1,
                w.baseName, w.fingerprint));

        DiagnosticLog.event(DiagnosticLog.Category.TRADE_MARKET, "transaction",
                Map.of("type", w.type.name(), "item", w.itemName,
                        "price", w.price > 0 ? w.price : 0, "source", "async_withdrawal"));

        // If this was a sell, also remove the matching pending sell context if one exists
        if (w.type == TransactionRecord.Type.SELL && w.baseName != null) {
            pendingSells.remove(w.baseName.toLowerCase());
        }
    }

    /**
     * Extract price from VIEWING_ORDER item lore.
     * Handles the format "X x Y²" where X is quantity (often 0 for fulfilled)
     * and Y is the unit price. Also falls back to standard price extraction.
     */
    private long extractOrderPrice(ItemStack stack) {
        try {
            String stackName = "?";
            try { stackName = stack.getHoverName().getString(); } catch (Exception ignored) {}
            List<String> loreLines = getPlainLoreLines(stack);

            // Priority 1: "X x Y" format (most specific to order screens)
            for (String text : loreLines) {
                Matcher m = ORDER_PRICE_PATTERN.matcher(text);
                if (m.find()) {
                    long unitPrice = Long.parseLong(m.group(2).replace(",", ""));
                    if (unitPrice > 0) {
                        tmLog("Order price from \"{}\" lore (XxY): unitPrice={}, line=\"{}\"",
                                stackName, unitPrice, text);
                        return unitPrice;
                    }
                }
            }

            // Priority 2: Explicit "Price:/Total:/Cost:" pattern
            for (String text : loreLines) {
                Matcher m = LORE_PRICE_PATTERN.matcher(text);
                if (m.find()) {
                    long p = Long.parseLong(m.group(1).replace(",", ""));
                    tmLog("Order price from \"{}\" lore (Price/Total): {}, line=\"{}\"",
                            stackName, p, text);
                    return p;
                }
            }

            // Priority 3: "X emeralds" pattern
            for (String text : loreLines) {
                Matcher m = LORE_EMERALD_PATTERN.matcher(text);
                if (m.find()) {
                    long p = Long.parseLong(m.group(1).replace(",", ""));
                    tmLog("Order price from \"{}\" lore (emeralds): {}, line=\"{}\"",
                            stackName, p, text);
                    return p;
                }
            }

            // Priority 4: Denomination format (last resort — prone to false positives)
            for (String text : loreLines) {
                long denomTotal = parseDenominations(text);
                if (denomTotal > 0) {
                    tmLog("Order price from \"{}\" lore (denomination): {}, line=\"{}\"",
                            stackName, denomTotal, text);
                    return denomTotal;
                }
            }
        } catch (Exception e) {
            tmLog("Order price extraction error: {}", e.getMessage());
        }

        return -1;
    }

    /**
     * Compute total emerald value from emerald item stacks in the trade area (slots 0-53,
     * excluding slot 28 item and slot 52 action button) of a VIEWING_ORDER container.
     * Returns -1 if no emerald items found.
     */
    private long computeEmeraldTotal(List<ItemStack> items) {
        long total = 0;
        boolean found = false;
        try {
            int limit = Math.min(items.size(), 54);
            for (int i = 0; i < limit; i++) {
                if (i == 28 || i == 46 || i == 52) continue;
                ItemStack stack = items.get(i);
                if (stack == null || stack.isEmpty()) continue;

                Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
                if (opt.isEmpty()) continue;
                WynnItem wynnItem = opt.get();

                // EmeraldItem covers Emerald, Emerald Block, Liquid Emerald
                if (wynnItem instanceof EmeraldItem) {
                    String name = ItemNameHelper.cleanItemName(stack.getHoverName().getString());
                    int count = stack.getCount();
                    long value = 0;
                    if (name != null) {
                        if (name.contains("Liquid Emerald")) {
                            value = count * LE_MULT;
                        } else if (name.contains("Emerald Block")) {
                            value = count * EB_MULT;
                        } else if (name.contains("Emerald")) {
                            value = count; // plain emeralds
                        }
                    }
                    if (value > 0) {
                        tmLog("Emerald stack at slot {}: name=\"{}\", count={}, value={}", i, name, count, value);
                        total += value;
                        found = true;
                    }
                }
            }
        } catch (Exception e) {
            tmWarn("Error computing emerald total: {}", e.getMessage());
        }
        return found ? total : -1;
    }

    /**
     * Get plain-text lore lines from an ItemStack, stripping formatting codes.
     */
    private List<String> getPlainLoreLines(ItemStack stack) {
        List<String> result = new ArrayList<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.item.Item.TooltipContext ctx;
            if (mc.level != null) {
                ctx = net.minecraft.world.item.Item.TooltipContext.of(mc.level);
            } else {
                ctx = net.minecraft.world.item.Item.TooltipContext.EMPTY;
            }
            var tooltipLines = stack.getTooltipLines(ctx, mc.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL);
            if (tooltipLines != null) {
                for (var line : tooltipLines) {
                    String text = line.getString();
                    if (text != null && !text.isEmpty()) {
                        result.add(text);
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Build a deterministic fingerprint from an identified gear item's stat rolls.
     * Returns null for unidentified or non-gear items.
     */
    private String buildStatFingerprint(ItemStack stack) {
        try {
            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isEmpty() || !(opt.get() instanceof GearItem gearItem)) return null;
            if (gearItem.getItemInstance().isEmpty()) return null;

            GearInstance instance = gearItem.getItemInstance().get();
            List<StatActualValue> stats = instance.identifications();
            if (stats == null || stats.isEmpty()) return null;

            String result = "v1:" + stats.stream()
                    .filter(s -> {
                        String api = s.statType().getApiName();
                        if (api == null || api.isEmpty()) {
                            tmWarn("Skipping stat with null/empty apiName in fingerprint");
                            return false;
                        }
                        if (api.contains(":") || api.contains(",")) {
                            tmWarn("Skipping stat with delimiter in apiName: \"{}\"", api);
                            return false;
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(s -> s.statType().getApiName()))
                    .map(s -> s.statType().getApiName() + ":" + s.value() + ":" + s.stars())
                    .collect(Collectors.joining(","));
            // If all stats were filtered out, the fingerprint is meaningless
            if (result.equals("v1:")) return null;
            return result;
        } catch (Exception e) {
            tmLog("Fingerprint build error: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract price from a list of items using prioritized pattern matching.
     * Scans ALL items for explicit "Price:/Total:/Cost:" first, then "X emeralds",
     * then denomination format last — to avoid picking up stray denomination text
     * (e.g., "1stx" from balance/fee display items) before the real price slot.
     */
    private long extractPrioritizedPrice(List<ItemStack> items) {
        long priceResult = -1;
        long emeraldResult = -1;
        long denomResult = -1;

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack == null || stack.isEmpty()) continue;

            try {
                Minecraft mc = Minecraft.getInstance();
                net.minecraft.world.item.Item.TooltipContext ctx;
                if (mc.level != null) {
                    ctx = net.minecraft.world.item.Item.TooltipContext.of(mc.level);
                } else {
                    ctx = net.minecraft.world.item.Item.TooltipContext.EMPTY;
                }
                List<Component> lore = stack.getTooltipLines(ctx, mc.player,
                        net.minecraft.world.item.TooltipFlag.NORMAL);
                if (lore == null) continue;

                for (Component line : lore) {
                    String text = line.getString();
                    if (text == null || text.isEmpty()) continue;

                    if (priceResult < 0) {
                        Matcher m = LORE_PRICE_PATTERN.matcher(text);
                        if (m.find()) {
                            priceResult = Long.parseLong(m.group(1).replace(",", ""));
                            tmLog("Sell price from explicit pattern (slot {}): {}", i, priceResult);
                        }
                    }
                    if (emeraldResult < 0) {
                        Matcher m = LORE_EMERALD_PATTERN.matcher(text);
                        if (m.find()) {
                            emeraldResult = Long.parseLong(m.group(1).replace(",", ""));
                        }
                    }
                    if (denomResult < 0) {
                        long d = parseDenominations(text);
                        if (d > 0) denomResult = d;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (priceResult > 0) return priceResult;
        if (emeraldResult > 0) return emeraldResult;
        return denomResult;
    }

    /**
     * Extract price from an item's tooltip/lore lines.
     * Tries multiple patterns: "Price: X", "Total: X", "X emeralds", denomination format.
     */
    private long extractPriceFromStack(ItemStack stack) {
        try {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.item.Item.TooltipContext ctx;
            if (mc.level != null) {
                ctx = net.minecraft.world.item.Item.TooltipContext.of(mc.level);
            } else {
                ctx = net.minecraft.world.item.Item.TooltipContext.EMPTY;
            }
            List<Component> lore = stack.getTooltipLines(ctx, mc.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL);
            if (lore == null) return -1;

            for (Component line : lore) {
                String text = line.getString();
                if (text == null || text.isEmpty()) continue;

                // "Price: 1,234" or "Total: 1,234" or "Cost: 1,234"
                Matcher m = LORE_PRICE_PATTERN.matcher(text);
                if (m.find()) return Long.parseLong(m.group(1).replace(",", ""));

                // "1,234 emeralds"
                m = LORE_EMERALD_PATTERN.matcher(text);
                if (m.find()) return Long.parseLong(m.group(1).replace(",", ""));

                // Denomination format "Xstx Yle Zeb We"
                long denomTotal = parseDenominations(text);
                if (denomTotal > 0) return denomTotal;
            }
        } catch (Exception e) {
            tmLog("Lore price extraction error: {}", e.getMessage());
        }
        return -1;
    }

    private long parseDenominations(String text) {
        long total = 0;
        boolean found = false;
        Matcher m = DENOM_PATTERN.matcher(text);
        while (m.find()) {
            found = true;
            long amount = Long.parseLong(m.group(1));
            String unit = m.group(2).toLowerCase();
            total += switch (unit) {
                case "stx" -> amount * STX_MULT;
                case "le" -> amount * LE_MULT;
                case "eb" -> amount * EB_MULT;
                case "e" -> amount;
                default -> 0;
            };
        }
        return found ? total : -1;
    }

    private void commitBuy() {
        // Prefer item name from "Finished buying [item]" chat message (most reliable)
        String name = lastFinishedItemName;

        // Fallback to pending context from BUYING container
        if (name == null || name.isEmpty()) {
            name = pendingItemName;
        }
        if (name == null || name.isEmpty()) {
            name = "Unknown Item";
        }

        long price = pendingPrice > 0 ? pendingPrice : 0;

        tmLog("Committing BUY: item=\"{}\", base=\"{}\", fingerprint={}, price={}",
                name, pendingBaseName,
                pendingFingerprint != null ? "yes" : "null", price);

        TransactionStore.addTransaction(new TransactionRecord(
                name, price, TransactionRecord.Type.BUY, "", 1,
                pendingBaseName, pendingFingerprint));

        DiagnosticLog.event(DiagnosticLog.Category.TRADE_MARKET, "transaction",
                Map.of("type", "BUY", "item", name, "price", price));

        // Track that this buy was committed via chat so commitWithdrawal() skips the duplicate
        String buyBase = pendingBaseName != null ? pendingBaseName : name;
        if (buyBase != null) {
            chatCommittedBuys.merge(buyBase.toLowerCase(), 1, Integer::sum);
            while (chatCommittedBuys.size() > 50) {
                var it = chatCommittedBuys.entrySet().iterator();
                it.next();
                it.remove();
            }
        }

        pendingItemName = null;
        pendingPrice = -1;
        pendingBaseName = null;
        pendingFingerprint = null;
        lastFinishedItemName = null;
    }

    private void commitSell() {
        // Prefer item name from "Finished selling [item]" chat message (most reliable)
        String name = lastFinishedItemName;

        // Fallback: use Wynntils API
        if (name == null || name.isEmpty()) {
            try {
                String soldName = Models.TradeMarket.getSoldItemName();
                if (soldName != null && !soldName.isEmpty()) {
                    name = ItemNameHelper.cleanItemName(soldName);
                }
            } catch (Exception ignored) {}
        }

        if (name == null || name.isEmpty()) {
            name = "Sold Item";
        }

        // Look up the matching pending sell context from the map.
        // Sells are asynchronous — the user may have listed many items and
        // they sell in arbitrary order, so we match by name.
        PendingSellContext ctx = findAndRemovePendingSell(name);

        long price = ctx != null && ctx.price > 0 ? ctx.price : 0;
        String baseName = ctx != null ? ctx.baseName : null;
        String fingerprint = ctx != null ? ctx.fingerprint : null;

        if (ctx == null) {
            tmWarn("No matching pending sell context for \"{}\". Recording with price=0.", name);
        }

        tmLog("Committing SELL: item=\"{}\", base=\"{}\", price={}", name, baseName, price);

        TransactionStore.addTransaction(new TransactionRecord(
                name, price, TransactionRecord.Type.SELL, "", 1,
                baseName, fingerprint));

        // Track that this sell was committed via chat so commitWithdrawal() skips the duplicate
        if (baseName != null) {
            chatCommittedSells.merge(baseName.toLowerCase(), 1, Integer::sum);
            // Keep map bounded
            while (chatCommittedSells.size() > 50) {
                var it = chatCommittedSells.entrySet().iterator();
                it.next();
                it.remove();
            }
        }

        DiagnosticLog.event(DiagnosticLog.Category.TRADE_MARKET, "transaction",
                Map.of("type", "SELL", "item", name, "price", price));

        lastFinishedItemName = null;
        matchedBuyRecord = null;
    }

    /**
     * Find and remove a pending sell context that matches the given item name.
     * Uses flexible matching to handle unidentified items (chat says "Grandmother"
     * but container captured "Unidentified Grandmother" with base "Grandmother").
     */
    private PendingSellContext findAndRemovePendingSell(String name) {
        if (name == null || pendingSells.isEmpty()) return null;
        String lower = name.toLowerCase();

        // Exact match on base name key
        PendingSellContext exact = pendingSells.remove(lower);
        if (exact != null) return exact;

        // Fuzzy match: check if chat name contains or is contained by a pending key
        for (var it = pendingSells.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            String key = entry.getKey();
            PendingSellContext ctx = entry.getValue();
            if (containsIgnoreCase(name, key) || containsIgnoreCase(key, name)
                    || (ctx.itemName != null && (
                            containsIgnoreCase(name, ctx.itemName)
                            || containsIgnoreCase(ctx.itemName, name)))) {
                it.remove();
                return ctx;
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────
    //  UTILITIES
    // ────────────────────────────────────────────────────────────────────

    private TradeMarketState safeGetState() {
        try {
            return Models.TradeMarket.getTradeMarketState();
        } catch (Exception e) {
            return TradeMarketState.NOT_ACTIVE;
        }
    }

    private boolean isTradeMarketRelevant() {
        try {
            return Models.TradeMarket.inTradeMarket()
                    || safeGetState() != TradeMarketState.NOT_ACTIVE;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }
}
