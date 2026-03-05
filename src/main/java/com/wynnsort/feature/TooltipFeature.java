package com.wynnsort.feature;

import com.wynnsort.SortState;
import com.wynnsort.StatFilter;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
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
 * Injects a WynnSort score line into item tooltips when hovering over gear items.
 * Uses Wynntils' ItemTooltipRenderEvent.Pre to add the line before the tooltip renders.
 */
public class TooltipFeature {

    public static final TooltipFeature INSTANCE = new TooltipFeature();

    private static final FeatureLogger LOG = new FeatureLogger("Tooltip", DiagnosticLog.Category.TOOLTIP);
    private boolean firstTooltipLogged = false;

    private TooltipFeature() {}

    @SubscribeEvent
    public void onItemTooltipRender(ItemTooltipRenderEvent.Pre event) {
        try {
            if (!WynnSortConfig.INSTANCE.overlayEnabled) {
                return;
            }

            ItemStack itemStack = event.getItemStack();
            if (itemStack.isEmpty()) {
                return;
            }

            Optional<WynnItem> wynnItemOpt = Models.Item.getWynnItem(itemStack);
            if (wynnItemOpt.isEmpty()) {
                return;
            }
            if (!(wynnItemOpt.get() instanceof GearItem gearItem)) {
                return;
            }

            Optional<GearInstance> gearInstanceOpt = gearItem.getItemInstance();
            if (gearInstanceOpt.isEmpty()) {
                return;
            }

            GearInstance gearInstance = gearInstanceOpt.get();
            List<StatFilter> filters = SortState.getFilters();

            ScoreResult result = computeScore(gearItem, gearInstance, filters);
            if (result == null) {
                // Score was NaN or negative
                String itemName = gearItem.getItemInfo().name();
                LOG.warn("Score NaN/negative for item={}, wynnItemType={}", itemName, wynnItemOpt.get().getClass().getSimpleName());
                return;
            }

            // Build the tooltip line: "WynnSort: XX% (label)"
            String colorCode = getColorCode(result.percentage);
            String line = "\u00A76WynnSort: " + colorCode + Math.round(result.percentage) + "% \u00A77(" + result.label + ")";

            List<Component> tooltips = new java.util.ArrayList<>(event.getTooltips());
            // Insert after the first line (item name) if possible, otherwise append
            if (tooltips.size() > 1) {
                tooltips.add(1, Component.literal(line));
            } else {
                tooltips.add(Component.literal(line));
            }
            event.setTooltips(tooltips);

            // Log first successful tooltip injection per session (exploratory)
            if (!firstTooltipLogged) {
                firstTooltipLogged = true;
                String itemName = gearItem.getItemInfo().name();
                LOG.info("First tooltip: item='{}', score={}%, label='{}', wynnItemClass={}",
                        itemName, Math.round(result.percentage), result.label,
                        wynnItemOpt.get().getClass().getSimpleName());
                LOG.event("first_tooltip", java.util.Map.of(
                        "item", itemName, "score", Math.round(result.percentage),
                        "label", result.label));
            }
        } catch (Exception e) {
            LOG.error("Error in tooltip render", e);
        }
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
