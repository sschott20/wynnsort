package com.wynnsort.feature;

import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.FeatureLogger;
import com.wynnsort.market.MarketPriceStore;
import com.wynnsort.market.PriceHistoryStore;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.ItemNameHelper;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ContainerSetSlotEvent;
import com.wynntils.models.trademarket.type.TradeMarketPriceInfo;
import com.wynntils.models.trademarket.type.TradeMarketState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarketPriceFeature {

    public static final MarketPriceFeature INSTANCE = new MarketPriceFeature();
    private static final FeatureLogger LOG = new FeatureLogger("Price", DiagnosticLog.Category.MARKET_PRICE);

    private static final Pattern FORMATTING_CODES = Pattern.compile("\u00A7.");
    // Matches a number (with optional commas) followed by the emerald symbol ² (U+00B2)
    private static final Pattern EMERALD_PRICE_PATTERN = Pattern.compile("(\\d[\\d,]*)\\u00B2");
    // Trade market container uses slots 0-44 for listings; 45+ is UI buttons and player inventory
    private static final int MAX_TRADE_SLOT = 44;

    private MarketPriceFeature() {}

    private boolean isTradeMarketBrowsing() {
        try {
            TradeMarketState state = Models.TradeMarket.getTradeMarketState();
            return state == TradeMarketState.DEFAULT_RESULTS
                    || state == TradeMarketState.FILTERED_RESULTS;
        } catch (Exception e) {
            return false;
        }
    }

    @SubscribeEvent
    public void onContainerContent(ContainerSetContentEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.marketPriceCacheEnabled) return;
        if (!isTradeMarketBrowsing()) return;

        List<ItemStack> items = event.getItems();
        if (items == null) return;

        long now = System.currentTimeMillis();
        int recorded = 0;
        int limit = Math.min(items.size(), MAX_TRADE_SLOT + 1);
        for (int i = 0; i < limit; i++) {
            ItemStack stack = items.get(i);
            if (stack == null || stack.isEmpty()) continue;
            if (recordPrice(stack, now)) recorded++;
        }
        if (recorded > 0) {
            LOG.info("recorded {}/{} items from container content", recorded, limit);
        }
    }

    /**
     * Captures prices from individual slot updates — this is how trade market
     * listings actually arrive (the server sends them one slot at a time after
     * the initial container content event).
     */
    @SubscribeEvent
    public void onSlotUpdate(ContainerSetSlotEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.marketPriceCacheEnabled) return;

        int slot = event.getSlot();
        if (slot < 0 || slot > MAX_TRADE_SLOT) return;

        if (!isTradeMarketBrowsing()) return;

        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) return;

        long now = System.currentTimeMillis();
        recordPrice(stack, now);
    }

    /**
     * Attempts to extract and record a price from a trade market item.
     * Tries Wynntils API first, then falls back to parsing the trade market lore.
     */
    private boolean recordPrice(ItemStack stack, long now) {
        try {
            String baseName = ItemNameHelper.extractBaseName(stack);
            if (baseName == null) return false;

            // Try Wynntils API first
            long price = -1;
            try {
                TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(stack);
                if (priceInfo != null && priceInfo != TradeMarketPriceInfo.EMPTY && priceInfo.price() > 0) {
                    price = priceInfo.price();
                }
            } catch (Exception ignored) {}

            // Fall back to parsing price from trade market lore
            if (price <= 0) {
                price = extractPriceFromTradeLore(stack);
            }

            if (price <= 0) return false;

            MarketPriceStore.record(baseName, price, now);
            PriceHistoryStore.record(baseName, price, now);
            LOG.info("recorded {}={} emeralds", baseName, price);
            DiagnosticLog.event(DiagnosticLog.Category.TRADE_MARKET, "price_recorded",
                    Map.of("item", baseName, "price", price));
            return true;
        } catch (Exception e) {
            LOG.warn("exception recording price for '{}'", stack.getHoverName().getString(), e);
            return false;
        }
    }

    /**
     * Extracts the per-unit listing price from trade market item lore.
     * Wynncraft trade market lore has a "Price" header line followed by a line
     * containing the emerald amount in the format: {number}² (where ² is U+00B2).
     * Example: "§f§m15,750§7§m²§b ✮ 15,450§3² §8(3¼² 49²½ 26²)"
     * The first {number}² before the denomination parentheses is the per-unit price.
     */
    private static long extractPriceFromTradeLore(ItemStack stack) {
        try {
            Minecraft mc = Minecraft.getInstance();
            var ctx = mc.level != null
                    ? net.minecraft.world.item.Item.TooltipContext.of(mc.level)
                    : net.minecraft.world.item.Item.TooltipContext.EMPTY;
            List<Component> lore = stack.getTooltipLines(ctx, mc.player, TooltipFlag.NORMAL);
            if (lore == null) return -1;

            boolean foundPriceHeader = false;
            for (Component line : lore) {
                String text = line.getString();
                if (text == null || text.isEmpty()) continue;

                String stripped = FORMATTING_CODES.matcher(text).replaceAll("");

                if (!foundPriceHeader) {
                    if (stripped.contains("Price")) {
                        foundPriceHeader = true;
                    }
                    continue;
                }

                // This line follows the Price header — extract the per-unit emerald price.
                // Remove denomination breakdown in parentheses to avoid false matches
                // like "49²½" (emerald blocks) being parsed as 49 emeralds.
                int parenIdx = stripped.indexOf('(');
                String pricePart = parenIdx >= 0 ? stripped.substring(0, parenIdx) : stripped;

                Matcher m = EMERALD_PRICE_PATTERN.matcher(pricePart);
                if (m.find()) {
                    return Long.parseLong(m.group(1).replace(",", ""));
                }
                break; // Only check the line immediately after the Price header
            }
        } catch (Exception e) {
            LOG.warn("lore price extraction error: {}", e.getMessage());
        }
        return -1;
    }

    public static String formatEmeralds(long price) {
        if (price >= 262144) {
            double stx = price / 262144.0;
            return String.format("%.1fstx", stx);
        } else if (price >= 4096) {
            double le = price / 4096.0;
            return String.format("%.1fle", le);
        } else if (price >= 64) {
            double eb = price / 64.0;
            return String.format("%.0feb", eb);
        } else {
            return price + "e";
        }
    }

    public static String formatAge(long ms) {
        if (ms < 60_000) return "now";
        long minutes = ms / 60_000;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        long days = hours / 24;
        return days + "d";
    }
}
