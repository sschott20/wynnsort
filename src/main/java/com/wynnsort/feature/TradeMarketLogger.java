package com.wynnsort.feature;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
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
import com.wynnsort.util.PersistentLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Comprehensive trade market observer. Has two responsibilities:
 *
 * 1. LOGGING — structured, permanent log entries prefixed [TM] that record
 *    every state transition, container snapshot, slot update, chat message,
 *    and Wynntils API value while the trade market is active.
 *
 * 2. TRANSACTION CAPTURE — detects buy/sell completions and writes them to
 *    TransactionStore for the history screen.
 */
public class TradeMarketLogger {

    public static final TradeMarketLogger INSTANCE = new TradeMarketLogger();

    private static final Logger TM = LoggerFactory.getLogger("wynnsort.trademarket");

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

    private static final long STX_MULT = 262144L;
    private static final long LE_MULT = 4096L;
    private static final long EB_MULT = 64L;

    // Tracked state
    private TradeMarketState lastKnownState = TradeMarketState.NOT_ACTIVE;

    // Pending purchase context (captured during BUYING state)
    private String pendingItemName = null;
    private long pendingPrice = -1;

    // Pending sell context (captured during SELLING state)
    private String pendingSellItemName = null;
    private long pendingSellPrice = -1;

    // Item name from "Finished buying/selling [item]" chat message
    // More reliable than pending context since it comes from the server
    private String lastFinishedItemName = null;

    // Pending fingerprint data for buy/sell context
    private String pendingBaseName = null;
    private String pendingFingerprint = null;
    private String pendingSellBaseName = null;
    private String pendingSellFingerprint = null;

    // Sell screen overlay: matched buy price to show when selling
    private static volatile TransactionRecord matchedBuyRecord = null;

    private TradeMarketLogger() {}

    private static void tmLog(String msg, Object... args) {
        TM.info(msg, args);
        PersistentLog.info(msg, args);
    }

    private static void tmWarn(String msg, Object... args) {
        TM.warn(msg, args);
        PersistentLog.warn(msg, args);
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
            tmLog("[TM] State change: {} -> {}", oldState, newState);
            logApiSnapshot("state-change");
        }

        // Clear sell overlay when leaving SELLING state
        if (oldState == TradeMarketState.SELLING && newState != TradeMarketState.SELLING) {
            matchedBuyRecord = null;
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

        tmLog("[TM] Screen opened: class={}, title=\"{}\"", screenClass, screenTitle);
        logContainerInfo("screen-open");
    }

    @SubscribeEvent
    public void onScreenClose(ScreenClosedEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.tradeMarketLogging) return;
        if (lastKnownState != TradeMarketState.NOT_ACTIVE) {
            tmLog("[TM] Screen closed (was in state: {})", lastKnownState);
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

        // Transaction capture: during BUYING or SELLING state
        if (WynnSortConfig.INSTANCE.tradeHistoryEnabled) {
            if (state == TradeMarketState.BUYING) {
                captureBuyContext(event.getItems());
            } else if (state == TradeMarketState.SELLING) {
                captureSellContext(event.getItems());
            }
        }
    }

    private void logContainerContent(ContainerSetContentEvent.Post event, TradeMarketState state) {
        List<ItemStack> items = event.getItems();
        if (items == null) return;

        logContainerInfo("content-update");

        tmLog("[TM] Container content: containerId={}, stateId={}, {} slots, state={}",
                event.getContainerId(), event.getStateId(), items.size(), state);

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack == null || stack.isEmpty()) continue;
            logSlotItem(i, stack);
        }

        logApiSnapshot("content-update");
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

        if (WynnSortConfig.INSTANCE.tradeMarketLogging) {
            if (stack == null || stack.isEmpty()) {
                tmLog("[TM] Slot cleared: container={}, slot={}", containerId, slot);
            } else {
                tmLog("[TM] Slot updated: container={}, slot={}", containerId, slot);
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
                    tmLog("[TM] Buy price captured from slot update {}: {}", slot, price);
                }
            } else if (state == TradeMarketState.SELLING && pendingSellPrice < 0) {
                long price = extractPriceFromStack(stack);
                if (price > 0) {
                    pendingSellPrice = price;
                    tmLog("[TM] Sell price captured from slot update {}: {}", slot, price);
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

        if (WynnSortConfig.INSTANCE.tradeMarketLogging && isTradeMarketRelevant()) {
            tmLog("[TM] Chat [{}]: \"{}\"", msgType, msg);
        }

        if (!WynnSortConfig.INSTANCE.tradeHistoryEnabled) return;

        try {
            // "Finished buying/selling [item]." fires right before claim messages
            Matcher buyMatch = FINISHED_BUYING_PATTERN.matcher(msg);
            if (buyMatch.find()) {
                lastFinishedItemName = ItemNameHelper.cleanItemName(buyMatch.group(1));
                tmLog("[TM] Finished buying: \"{}\"", lastFinishedItemName);
            }
            Matcher sellMatch = FINISHED_SELLING_PATTERN.matcher(msg);
            if (sellMatch.find()) {
                lastFinishedItemName = ItemNameHelper.cleanItemName(sellMatch.group(1));
                tmLog("[TM] Finished selling: \"{}\"", lastFinishedItemName);
            }

            if (CLAIM_ITEMS_PATTERN.matcher(msg).find()) {
                tmLog("[TM] >>> BUY confirmation detected");
                logApiSnapshot("buy-confirm");
                commitBuy();
            } else if (CLAIM_EMERALDS_PATTERN.matcher(msg).find()) {
                tmLog("[TM] >>> SELL confirmation detected");
                logApiSnapshot("sell-confirm");
                commitSell();
            }
        } catch (Exception e) {
            tmWarn("[TM] Error processing trade message", e);
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
                            wynnDetail += " overall=" + gi.getItemInstance().get().getOverallPercentage() + "%";
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

        // Lore — use proper tooltip context with player and level
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

        tmLog("[TM]   slot[{}]: name=\"{}\" wynnType={}{}{}{}", slot, hoverName,
                wynnType, wynnDetail, priceStr,
                lore.length() > 0 ? "\n            lore:" + lore : "");
    }

    private void logContainerInfo(String trigger) {
        try {
            Container container = Models.Container.getCurrentContainer();
            if (container != null) {
                tmLog("[TM] Container [{}]: class={}, name=\"{}\", id={}",
                        trigger,
                        container.getClass().getSimpleName(),
                        container.getContainerName(),
                        container.getContainerId());
            } else {
                tmLog("[TM] Container [{}]: null", trigger);
            }
        } catch (Exception e) {
            tmLog("[TM] Container [{}]: error reading - {}", trigger, e.getMessage());
        }
    }

    private void logApiSnapshot(String trigger) {
        try {
            TradeMarketState state = Models.TradeMarket.getTradeMarketState();
            boolean inTM = Models.TradeMarket.inTradeMarket();
            boolean inChat = Models.TradeMarket.inChatInput();

            tmLog("[TM] API [{}]: state={}, inTradeMarket={}, inChatInput={}",
                    trigger, state, inTM, inChat);

            try {
                int unitPrice = Models.TradeMarket.getUnitPrice();
                tmLog("[TM] API [{}]: unitPrice={}", trigger, unitPrice);
            } catch (Exception e) {
                tmLog("[TM] API [{}]: unitPrice=error({})", trigger, e.getMessage());
            }

            try {
                String soldName = Models.TradeMarket.getSoldItemName();
                tmLog("[TM] API [{}]: soldItemName=\"{}\"", trigger, soldName);
            } catch (Exception e) {
                tmLog("[TM] API [{}]: soldItemName=error({})", trigger, e.getMessage());
            }

            try {
                var pci = Models.TradeMarket.getPriceCheckInfo();
                if (pci != null) {
                    tmLog("[TM] API [{}]: priceCheck: recommended={}, bid={}, ask={}",
                            trigger, pci.recommendedPrice(), pci.bid(), pci.ask());
                }
            } catch (Exception e) {
                tmLog("[TM] API [{}]: priceCheck=error({})", trigger, e.getMessage());
            }

            try {
                String filter = Models.TradeMarket.getLastSearchFilter();
                if (filter != null && !filter.isEmpty()) {
                    tmLog("[TM] API [{}]: lastSearchFilter=\"{}\"", trigger, filter);
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            tmWarn("[TM] API snapshot failed [{}]", trigger, e);
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
                            tmLog("[TM] Price from Wynntils API (slot {}): {}", i, foundPrice);
                        }
                    } catch (Exception ignored) {}
                }

                // Fallback: parse price from item lore
                if (foundPrice < 0) {
                    foundPrice = extractPriceFromStack(stack);
                    if (foundPrice > 0) {
                        tmLog("[TM] Price from lore parsing (slot {}): {}", i, foundPrice);
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
                tmLog("[TM] Pending buy captured: item=\"{}\", base=\"{}\", fingerprint={}, price={}",
                        pendingItemName, pendingBaseName,
                        foundFingerprint != null ? "yes(" + foundFingerprint.length() + "chars)" : "null",
                        pendingPrice);
            }
        } catch (Exception e) {
            tmWarn("[TM] Error capturing buy context", e);
        }
    }

    private void captureSellContext(List<ItemStack> items) {
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
                            tmLog("[TM] Sell price from Wynntils API (slot {}): {}", i, foundPrice);
                        }
                    } catch (Exception ignored) {}
                }

                // Fallback: parse price from item lore (e.g., "Total: 200" in Set Price slot)
                if (foundPrice < 0) {
                    foundPrice = extractPriceFromStack(stack);
                    if (foundPrice > 0) {
                        tmLog("[TM] Sell price from lore parsing (slot {}): {}", i, foundPrice);
                    }
                }
            }

            if (foundItem != null) {
                pendingSellItemName = ItemNameHelper.cleanItemName(foundItem);
                pendingSellBaseName = foundBase;
                pendingSellFingerprint = foundFingerprint;
            }
            if (foundPrice > 0) {
                pendingSellPrice = foundPrice;
            }

            // Look up matching buy record for the sell overlay
            if (foundBase != null) {
                TransactionRecord match = TransactionStore.findMatchingBuy(foundBase, foundFingerprint);
                matchedBuyRecord = match;
                if (match != null) {
                    tmLog("[TM] Sell overlay: matched buy for \"{}\" at price {}",
                            match.itemName, match.priceEmeralds);
                } else {
                    tmLog("[TM] Sell overlay: no matching buy found for \"{}\"", foundBase);
                }
            }

            tmLog("[TM] Pending sell captured: item=\"{}\", base=\"{}\", fingerprint={}, price={}",
                    pendingSellItemName, foundBase,
                    foundFingerprint != null ? "yes(" + foundFingerprint.length() + "chars)" : "null",
                    pendingSellPrice);
        } catch (Exception e) {
            tmWarn("[TM] Error capturing sell context", e);
        }
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

            return "v1:" + stats.stream()
                    .filter(s -> {
                        String api = s.statType().getApiName();
                        if (api == null || api.isEmpty()) {
                            tmWarn("[TM] Skipping stat with null/empty apiName in fingerprint");
                            return false;
                        }
                        if (api.contains(":") || api.contains(",")) {
                            tmWarn("[TM] Skipping stat with delimiter in apiName: \"{}\"", api);
                            return false;
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(s -> s.statType().getApiName()))
                    .map(s -> s.statType().getApiName() + ":" + s.value() + ":" + s.stars())
                    .collect(Collectors.joining(","));
        } catch (Exception e) {
            tmLog("[TM] Fingerprint build error: {}", e.getMessage());
        }
        return null;
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
            tmLog("[TM] Lore price extraction error: {}", e.getMessage());
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

        tmLog("[TM] Committing BUY: item=\"{}\", base=\"{}\", fingerprint={}, price={}",
                name, pendingBaseName,
                pendingFingerprint != null ? "yes" : "null", price);

        TransactionStore.addTransaction(new TransactionRecord(
                name, price, TransactionRecord.Type.BUY, "", 1,
                pendingBaseName, pendingFingerprint));

        pendingItemName = null;
        pendingPrice = -1;
        pendingBaseName = null;
        pendingFingerprint = null;
        lastFinishedItemName = null;
    }

    private void commitSell() {
        // Prefer item name from "Finished selling [item]" chat message (most reliable)
        String name = lastFinishedItemName;

        // Fallback to pending context from SELLING container
        if (name == null || name.isEmpty()) {
            name = pendingSellItemName;
        }

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

        // Use pending sell price if names match (flexible matching to handle
        // unidentified items where chat says "Propeller Hat" but container
        // captured "Unidentified Propeller Hat")
        long price = 0;
        String baseName = pendingSellBaseName;
        String fingerprint = pendingSellFingerprint;
        if (pendingSellPrice > 0) {
            boolean match = pendingSellItemName == null
                    || pendingSellItemName.equalsIgnoreCase(name)
                    || containsIgnoreCase(name, pendingSellItemName)
                    || containsIgnoreCase(pendingSellItemName, name)
                    || (pendingSellBaseName != null && (
                            pendingSellBaseName.equalsIgnoreCase(name)
                            || containsIgnoreCase(name, pendingSellBaseName)
                            || containsIgnoreCase(pendingSellBaseName, name)));
            if (match) {
                price = pendingSellPrice;
            } else {
                tmWarn("[TM] Sell name mismatch: chat=\"{}\", pending=\"{}\", base=\"{}\". Using price anyway.",
                        name, pendingSellItemName, pendingSellBaseName);
                // Still use the price — sell flow is linear, stale data is unlikely
                price = pendingSellPrice;
            }
        }

        tmLog("[TM] Committing SELL: item=\"{}\", base=\"{}\", price={}", name, baseName, price);

        TransactionStore.addTransaction(new TransactionRecord(
                name, price, TransactionRecord.Type.SELL, "", 1,
                baseName, fingerprint));

        pendingSellItemName = null;
        pendingSellPrice = -1;
        pendingSellBaseName = null;
        pendingSellFingerprint = null;
        lastFinishedItemName = null;
        matchedBuyRecord = null;
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
