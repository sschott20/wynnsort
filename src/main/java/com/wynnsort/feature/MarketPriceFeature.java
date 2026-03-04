package com.wynnsort.feature;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.market.MarketPriceEntry;
import com.wynnsort.market.MarketPriceStore;
import com.wynnsort.util.ItemNameHelper;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.trademarket.type.TradeMarketPriceInfo;
import com.wynntils.models.trademarket.type.TradeMarketState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;
import java.util.Optional;

public class MarketPriceFeature {

    public static final MarketPriceFeature INSTANCE = new MarketPriceFeature();

    private MarketPriceFeature() {}

    @SubscribeEvent
    public void onContainerContent(ContainerSetContentEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.marketPriceCacheEnabled) return;

        TradeMarketState state;
        try {
            state = Models.TradeMarket.getTradeMarketState();
        } catch (Exception e) {
            WynnSortMod.log("[WynnSort] MarketPrice: failed to get trade market state", e);
            return;
        }

        // Only capture from search result screens
        if (state != TradeMarketState.DEFAULT_RESULTS && state != TradeMarketState.FILTERED_RESULTS) {
            return;
        }

        List<ItemStack> items = event.getItems();
        if (items == null) return;

        WynnSortMod.log("[WynnSort] MarketPrice: processing {} items in state {}", items.size(), state);

        long now = System.currentTimeMillis();
        int recorded = 0;
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;

            try {
                // Debug: log the WynnItem type before extracting base name
                Optional<WynnItem> wynnOpt = Models.Item.getWynnItem(stack);
                String wynnType = wynnOpt.map(w -> w.getClass().getSimpleName()).orElse("none");
                String hoverName = stack.getHoverName().getString();

                String baseName = ItemNameHelper.extractBaseName(stack);
                if (baseName == null) {
                    WynnSortMod.log("[WynnSort] MarketPrice: null baseName for '{}' (WynnItem={})", hoverName, wynnType);
                    continue;
                }

                TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(stack);
                if (priceInfo == null || priceInfo == TradeMarketPriceInfo.EMPTY || priceInfo.price() <= 0) {
                    WynnSortMod.log("[WynnSort] MarketPrice: no price for '{}' (priceInfo={})", baseName,
                            priceInfo == null ? "null" : priceInfo == TradeMarketPriceInfo.EMPTY ? "EMPTY" : "price=" + priceInfo.price());
                    continue;
                }

                MarketPriceStore.record(baseName, priceInfo.price(), now);
                recorded++;
                WynnSortMod.log("[WynnSort] MarketPrice: recorded {}={} emeralds", baseName, priceInfo.price());
            } catch (Exception e) {
                WynnSortMod.logWarn("[WynnSort] MarketPrice: exception processing item '{}'", stack.getHoverName().getString(), e);
            }
        }
        WynnSortMod.log("[WynnSort] MarketPrice: recorded {}/{} items", recorded, items.size());
    }

    @SubscribeEvent
    public void onTooltip(ItemTooltipRenderEvent.Pre event) {
        if (!WynnSortConfig.INSTANCE.marketPriceCacheEnabled) return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;

        String baseName = ItemNameHelper.extractBaseName(stack);
        if (baseName == null) return;

        MarketPriceEntry entry = MarketPriceStore.getPrice(baseName);
        if (entry == null) return;

        String priceStr = formatEmeralds(entry.price);
        String ageStr = formatAge(System.currentTimeMillis() - entry.timestamp);
        String line = "\u00A77Market: \u00A7e" + priceStr + " \u00A78(" + ageStr + " ago)";

        List<Component> tooltips = event.getTooltips();
        if (tooltips.size() > 1) {
            tooltips.add(1, Component.literal(line));
        } else {
            tooltips.add(Component.literal(line));
        }
    }

    private static String formatEmeralds(long price) {
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

    private static String formatAge(long ms) {
        if (ms < 60_000) return "now";
        long minutes = ms / 60_000;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        long days = hours / 24;
        return days + "d";
    }
}
