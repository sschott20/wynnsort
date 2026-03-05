package com.wynnsort.feature;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.FeatureLogger;
import com.wynnsort.market.CrowdsourceClient;
import com.wynnsort.market.MarketPriceEntry;
import com.wynnsort.market.MarketPriceStore;
import com.wynnsort.market.PriceHistoryStore;
import com.wynnsort.market.PriceStats;
import com.wynnsort.market.PriceTrend;
import com.wynnsort.util.DiagnosticLog;
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
import java.util.Map;
import java.util.Optional;

public class MarketPriceFeature {

    public static final MarketPriceFeature INSTANCE = new MarketPriceFeature();
    private static final FeatureLogger LOG = new FeatureLogger("Price", DiagnosticLog.Category.MARKET_PRICE);

    private MarketPriceFeature() {}

    @SubscribeEvent
    public void onContainerContent(ContainerSetContentEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.marketPriceCacheEnabled) return;

        TradeMarketState state;
        try {
            state = Models.TradeMarket.getTradeMarketState();
        } catch (Exception e) {
            LOG.info("failed to get trade market state", e);
            return;
        }

        // Only capture from search result screens
        if (state != TradeMarketState.DEFAULT_RESULTS && state != TradeMarketState.FILTERED_RESULTS) {
            return;
        }

        List<ItemStack> items = event.getItems();
        if (items == null) return;

        LOG.info("processing {} items in state {}", items.size(), state);

        long now = System.currentTimeMillis();
        int recorded = 0;
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;

            try {
                // Exploratory: log WynnItem class names for discovering interfaces
                Optional<WynnItem> wynnOpt = Models.Item.getWynnItem(stack);
                String wynnType = wynnOpt.map(w -> w.getClass().getSimpleName()).orElse("none");
                String wynnFullClass = wynnOpt.map(w -> w.getClass().getName()).orElse("none");
                String hoverName = stack.getHoverName().getString();
                LOG.info("Item: hover='{}', wynnType={}, fullClass={}", hoverName, wynnType, wynnFullClass);

                String baseName = ItemNameHelper.extractBaseName(stack);
                if (baseName == null) {
                    LOG.info("null baseName for '{}' (WynnItem={})", hoverName, wynnType);
                    continue;
                }

                TradeMarketPriceInfo priceInfo = Models.TradeMarket.calculateItemPriceInfo(stack);
                if (priceInfo == null || priceInfo == TradeMarketPriceInfo.EMPTY || priceInfo.price() <= 0) {
                    LOG.info("no price for '{}' (priceInfo={})", baseName,
                            priceInfo == null ? "null" : priceInfo == TradeMarketPriceInfo.EMPTY ? "EMPTY" : "price=" + priceInfo.price());
                    continue;
                }

                MarketPriceStore.record(baseName, priceInfo.price(), now);
                PriceHistoryStore.record(baseName, priceInfo.price(), now);
                recorded++;
                LOG.info("recorded {}={} emeralds", baseName, priceInfo.price());
                DiagnosticLog.event(DiagnosticLog.Category.TRADE_MARKET, "price_recorded",
                        Map.of("item", baseName, "price", priceInfo.price()));
            } catch (Exception e) {
                LOG.warn("exception processing item '{}'", stack.getHoverName().getString(), e);
            }
        }
        LOG.info("recorded {}/{} items", recorded, items.size());
    }

    @SubscribeEvent
    public void onTooltip(ItemTooltipRenderEvent.Pre event) {
        if (!WynnSortConfig.INSTANCE.marketPriceCacheEnabled) return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;

        String baseName = ItemNameHelper.extractBaseName(stack);
        if (baseName == null) return;

        MarketPriceEntry entry = MarketPriceStore.getPrice(baseName);
        if (entry == null && !WynnSortConfig.INSTANCE.crowdsourceEnabled) return;

        List<Component> tooltips = event.getTooltips();
        int insertIndex = tooltips.size() > 1 ? 1 : tooltips.size();
        int linesAdded = 0;

        // Build the main price line with optional trend arrow
        if (entry != null) {
            String priceStr = formatEmeralds(entry.price);
            String ageStr = formatAge(System.currentTimeMillis() - entry.timestamp);
            PriceStats stats = WynnSortConfig.INSTANCE.priceHistoryEnabled
                    ? PriceHistoryStore.getStats(baseName) : null;

            StringBuilder mainLine = new StringBuilder();
            mainLine.append("\u00A77Market: \u00A7e").append(priceStr);
            mainLine.append(" \u00A78(").append(ageStr).append(" ago)");

            if (stats != null && stats.trend() != PriceTrend.UNKNOWN) {
                mainLine.append(" ");
                switch (stats.trend()) {
                    case RISING -> mainLine.append("\u00A7a\u25B2"); // green up arrow
                    case FALLING -> mainLine.append("\u00A7c\u25BC"); // red down arrow
                    case STABLE -> mainLine.append("\u00A77\u2500"); // gray dash
                    default -> {} // UNKNOWN - no arrow
                }
            }

            tooltips.add(insertIndex + linesAdded, Component.literal(mainLine.toString()));
            linesAdded++;

            // Add range line if we have history data
            if (stats != null && stats.count() > 1) {
                String minStr = formatEmeralds(stats.min());
                String maxStr = formatEmeralds(stats.max());
                String rangeLine = "\u00A77Range: \u00A7f" + minStr + " \u00A77- \u00A7f" + maxStr
                        + " \u00A78(" + stats.count() + " seen)";
                tooltips.add(insertIndex + linesAdded, Component.literal(rangeLine));
                linesAdded++;
            }
        }

        // Add crowdsource local aggregation line
        if (WynnSortConfig.INSTANCE.crowdsourceEnabled) {
            try {
                CrowdsourceClient.LocalAggregation localAgg =
                        CrowdsourceClient.INSTANCE.getLocalAggregation(baseName);
                if (localAgg != null && localAgg.count > 0) {
                    String avgStr = formatEmeralds(localAgg.avg);
                    String localLine = "\u00A77Local avg: \u00A7b" + avgStr
                            + " \u00A78(" + localAgg.count + " seen)";
                    tooltips.add(insertIndex + linesAdded, Component.literal(localLine));
                    linesAdded++;
                }

                // Add community data line if available
                CrowdsourceClient.CommunityPriceData community =
                        CrowdsourceClient.INSTANCE.getCommunityData(baseName);
                if (community != null && community.listingCount > 0) {
                    String communityAvgStr = formatEmeralds(community.avgPrice);
                    String communityLine = "\u00A77Community: \u00A7d" + communityAvgStr
                            + " avg \u00A78(" + community.listingCount + " listings)";
                    tooltips.add(insertIndex + linesAdded, Component.literal(communityLine));
                    linesAdded++;
                }
            } catch (Exception e) {
                LOG.warn("Crowdsource tooltip error: {}", e.getMessage());
            }
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
