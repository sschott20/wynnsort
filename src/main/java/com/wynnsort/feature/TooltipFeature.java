package com.wynnsort.feature;

import com.wynnsort.SortState;
import com.wynnsort.StatFilter;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.market.MarketPriceEntry;
import com.wynnsort.market.MarketPriceStore;
import com.wynnsort.market.PriceHistoryStore;
import com.wynnsort.market.PriceStats;
import com.wynnsort.market.PriceTrend;
import com.wynnsort.market.CrowdsourceClient;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynnsort.util.ItemNameHelper;
import com.wynnsort.util.ScoreComputation;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;
import java.util.Optional;

/**
 * Single tooltip handler for all WynnSort tooltip additions (score + market price).
 * Wynntils' event.getTooltips() returns the original list on each handler call,
 * so only the last setTooltips() call takes effect. Having one handler avoids overwrites.
 */
public class TooltipFeature {

    public static final TooltipFeature INSTANCE = new TooltipFeature();

    private static final FeatureLogger LOG = new FeatureLogger("Tooltip", DiagnosticLog.Category.TOOLTIP);
    private boolean firstTooltipLogged = false;
    private boolean scoreComputationBroken = false;

    private TooltipFeature() {}

    @SubscribeEvent
    public void onItemTooltipRender(ItemTooltipRenderEvent.Pre event) {
        ItemStack itemStack = event.getItemStack();
        if (itemStack.isEmpty()) return;

        List<Component> tooltips = new java.util.ArrayList<>(event.getTooltips());
        int insertIndex = tooltips.size() > 1 ? 1 : tooltips.size();
        boolean modified = false;

        // --- Score line (for identified gear only) ---
        modified |= addScoreLine(itemStack, tooltips, insertIndex);

        // --- Market price lines (for any game item with cached price) ---
        modified |= addPriceLines(itemStack, tooltips, insertIndex + (modified ? 1 : 0));

        if (modified) {
            event.setTooltips(tooltips);
        }
    }

    private boolean addScoreLine(ItemStack itemStack, List<Component> tooltips, int insertIndex) {
        try {
            if (!WynnSortConfig.INSTANCE.overlayEnabled) return false;
            if (scoreComputationBroken) return false;

            Optional<WynnItem> wynnItemOpt = Models.Item.getWynnItem(itemStack);
            if (wynnItemOpt.isEmpty()) return false;
            if (!(wynnItemOpt.get() instanceof GearItem gearItem)) return false;

            Optional<GearInstance> gearInstanceOpt = gearItem.getItemInstance();
            if (gearInstanceOpt.isEmpty()) return false;

            GearInstance gearInstance = gearInstanceOpt.get();
            List<StatFilter> filters = SortState.getFilters();

            ScoreResult result = computeScore(gearItem, gearInstance, filters);
            if (result == null) {
                LOG.warn("Score NaN/negative for item={}", gearItem.getItemInfo().name());
                return false;
            }

            String colorCode = getColorCode(result.percentage);
            String line = "\u00A76WynnSort: " + colorCode + Math.round(result.percentage) + "% \u00A77(" + result.label + ")";
            tooltips.add(insertIndex, Component.literal(line));

            if (!firstTooltipLogged) {
                firstTooltipLogged = true;
                String itemName = gearItem.getItemInfo().name();
                LOG.info("First tooltip: item='{}', score={}%, label='{}', wynnItemClass={}",
                        itemName, Math.round(result.percentage), result.label,
                        wynnItemOpt.get().getClass().getSimpleName());
            }
            return true;
        } catch (Exception | NoClassDefFoundError e) {
            if (!scoreComputationBroken) {
                LOG.error("ScoreComputation failed in tooltip, disabling until restart: {}", e.getMessage());
                scoreComputationBroken = true;
            }
            return false;
        }
    }

    private boolean addPriceLines(ItemStack itemStack, List<Component> tooltips, int insertIndex) {
        if (!WynnSortConfig.INSTANCE.marketPriceCacheEnabled) return false;

        String baseName = ItemNameHelper.extractBaseName(itemStack);
        if (baseName == null) return false;

        MarketPriceEntry entry = MarketPriceStore.getPrice(baseName);
        if (entry == null && !WynnSortConfig.INSTANCE.crowdsourceEnabled) return false;

        int linesAdded = 0;

        if (entry != null) {
            int taxPercent = WynnSortConfig.INSTANCE.tradeMarketBuyTaxPercent;
            long displayPrice = taxPercent > 0
                    ? entry.price + (entry.price * taxPercent / 100)
                    : entry.price;
            String priceStr = MarketPriceFeature.formatEmeralds(displayPrice);
            String ageStr = MarketPriceFeature.formatAge(System.currentTimeMillis() - entry.timestamp);
            PriceStats stats = WynnSortConfig.INSTANCE.priceHistoryEnabled
                    ? PriceHistoryStore.getStats(baseName) : null;

            StringBuilder mainLine = new StringBuilder();
            mainLine.append("\u00A77Market: \u00A7e").append(priceStr);
            if (taxPercent > 0) {
                mainLine.append(" \u00A78(+").append(taxPercent).append("% tax)");
            }
            mainLine.append(" \u00A78(").append(ageStr).append(" ago)");

            if (stats != null && stats.trend() != PriceTrend.UNKNOWN) {
                mainLine.append(" ");
                switch (stats.trend()) {
                    case RISING -> mainLine.append("\u00A7a\u25B2");
                    case FALLING -> mainLine.append("\u00A7c\u25BC");
                    case STABLE -> mainLine.append("\u00A77\u2500");
                    default -> {}
                }
            }

            tooltips.add(insertIndex + linesAdded, Component.literal(mainLine.toString()));
            linesAdded++;
        }

        if (WynnSortConfig.INSTANCE.crowdsourceEnabled) {
            try {
                CrowdsourceClient.LocalAggregation localAgg =
                        CrowdsourceClient.INSTANCE.getLocalAggregation(baseName);
                if (localAgg != null && localAgg.count > 0) {
                    String localLine = "\u00A77Local avg: \u00A7b" + MarketPriceFeature.formatEmeralds(localAgg.avg)
                            + " \u00A78(" + localAgg.count + " seen)";
                    tooltips.add(insertIndex + linesAdded, Component.literal(localLine));
                    linesAdded++;
                }

                CrowdsourceClient.CommunityPriceData community =
                        CrowdsourceClient.INSTANCE.getCommunityData(baseName);
                if (community != null && community.listingCount > 0) {
                    String communityLine = "\u00A77Community: \u00A7d" + MarketPriceFeature.formatEmeralds(community.avgPrice)
                            + " avg \u00A78(" + community.listingCount + " listings)";
                    tooltips.add(insertIndex + linesAdded, Component.literal(communityLine));
                    linesAdded++;
                }
            } catch (Exception e) {
                LOG.warn("Crowdsource tooltip error: {}", e.getMessage());
            }
        }

        return linesAdded > 0;
    }

    private ScoreResult computeScore(GearItem gearItem, GearInstance gearInstance, List<StatFilter> filters) {
        float pct = ScoreComputation.computeScore(gearItem, gearInstance, filters);
        if (Float.isNaN(pct) || pct < 0.0f) return null;
        String label = SortState.isOverall() ? "overall"
                : SortState.isFilterMode() ? "filtered avg"
                : filters.get(0).statPattern();
        return new ScoreResult(pct, label);
    }

    /**
     * Returns the section sign color code for the given percentage.
     */
    private String getColorCode(float percentage) {
        if (percentage >= 95.0f) return "\u00A7b"; // aqua
        if (percentage >= 80.0f) return "\u00A7a"; // green
        if (percentage >= 60.0f) return "\u00A7e"; // yellow
        if (percentage >= 30.0f) return "\u00A76"; // gold/orange
        return "\u00A7c";                           // red
    }

    private record ScoreResult(float percentage, String label) {}
}
