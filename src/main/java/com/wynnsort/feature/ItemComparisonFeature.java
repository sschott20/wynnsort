package com.wynnsort.feature;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import com.wynntils.models.gear.type.GearInfo;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.gear.type.GearType;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.stats.type.StatActualValue;
import com.wynntils.models.stats.type.StatType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.*;

/**
 * Injects comparison lines into gear item tooltips when holding Shift,
 * showing stat differences between the hovered item and currently equipped gear.
 */
public class ItemComparisonFeature {

    public static final ItemComparisonFeature INSTANCE = new ItemComparisonFeature();

    private ItemComparisonFeature() {}

    @SubscribeEvent
    public void onItemTooltipRender(ItemTooltipRenderEvent.Pre event) {
        if (!WynnSortConfig.INSTANCE.itemComparisonEnabled) {
            return;
        }

        ItemStack itemStack = event.getItemStack();
        if (itemStack.isEmpty()) {
            return;
        }

        // Resolve the hovered item as a GearItem
        Optional<WynnItem> wynnItemOpt = Models.Item.getWynnItem(itemStack);
        if (wynnItemOpt.isEmpty()) {
            return;
        }
        if (!(wynnItemOpt.get() instanceof GearItem hoveredGearItem)) {
            return;
        }

        Optional<GearInstance> hoveredInstanceOpt = hoveredGearItem.getItemInstance();
        if (hoveredInstanceOpt.isEmpty()) {
            return;
        }

        List<Component> tooltips = event.getTooltips();

        // Always show the hint line at the end
        if (!Screen.hasShiftDown()) {
            tooltips.add(Component.literal("\u00A78[Hold Shift to compare]"));
            return;
        }

        GearInstance hoveredInstance = hoveredInstanceOpt.get();
        GearInfo hoveredInfo = hoveredGearItem.getItemInfo();
        GearType gearType = hoveredInfo.type();

        // Find the equipped item of the same gear type
        ItemStack equippedStack = findEquippedItem(gearType);
        if (equippedStack == null || equippedStack.isEmpty()) {
            tooltips.add(Component.literal("\u00A76--- vs Equipped ---"));
            tooltips.add(Component.literal("\u00A77No equipped item of this type"));
            return;
        }

        // Resolve the equipped item as a GearItem
        Optional<WynnItem> equippedWynnOpt = Models.Item.getWynnItem(equippedStack);
        if (equippedWynnOpt.isEmpty() || !(equippedWynnOpt.get() instanceof GearItem equippedGearItem)) {
            tooltips.add(Component.literal("\u00A76--- vs Equipped ---"));
            tooltips.add(Component.literal("\u00A77Equipped item is not identifiable gear"));
            return;
        }

        Optional<GearInstance> equippedInstanceOpt = equippedGearItem.getItemInstance();
        if (equippedInstanceOpt.isEmpty()) {
            tooltips.add(Component.literal("\u00A76--- vs Equipped ---"));
            tooltips.add(Component.literal("\u00A77Equipped item is unidentified"));
            return;
        }

        GearInstance equippedInstance = equippedInstanceOpt.get();

        // Build comparison lines
        tooltips.add(Component.literal("\u00A76--- vs Equipped ---"));

        // Overall percentage comparison
        addOverallComparison(tooltips, hoveredInstance, equippedInstance);

        // Per-stat comparison
        addStatComparisons(tooltips, hoveredInstance, equippedInstance);
    }

    /**
     * Finds the currently equipped item matching the given gear type.
     */
    private ItemStack findEquippedItem(GearType gearType) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }

        Inventory inventory = mc.player.getInventory();

        // Armor slots: boots=0, leggings=1, chestplate=2, helmet=3
        if (gearType == GearType.BOOTS) {
            return inventory.getArmor(0);
        } else if (gearType == GearType.LEGGINGS) {
            return inventory.getArmor(1);
        } else if (gearType == GearType.CHESTPLATE) {
            return inventory.getArmor(2);
        } else if (gearType == GearType.HELMET) {
            return inventory.getArmor(3);
        }

        // Weapons go in main hand
        if (gearType == GearType.WAND || gearType == GearType.SPEAR
                || gearType == GearType.BOW || gearType == GearType.DAGGER
                || gearType == GearType.RELIK) {
            return inventory.getSelected();
        }

        // Accessories (ring, bracelet, necklace) — scan the accessory slots
        // Wynncraft uses specific inventory slots for accessories, but the
        // exact slot varies. We scan the full inventory for a matching accessory type.
        if (gearType == GearType.RING || gearType == GearType.BRACELET
                || gearType == GearType.NECKLACE || gearType == GearType.ACCESSORY) {
            return findAccessoryInInventory(inventory, gearType);
        }

        return null;
    }

    /**
     * Scans the player inventory for an equipped accessory of the given type.
     * Accessories in Wynncraft occupy specific slots, but we scan all slots and
     * return the first matching accessory type found.
     */
    private ItemStack findAccessoryInInventory(Inventory inventory, GearType targetType) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            Optional<WynnItem> wynnOpt = Models.Item.getWynnItem(stack);
            if (wynnOpt.isEmpty()) continue;
            if (!(wynnOpt.get() instanceof GearItem gearItem)) continue;

            GearType itemType = gearItem.getItemInfo().type();
            if (itemType == targetType) {
                return stack;
            }
            // If searching for ACCESSORY (generic), match any accessory subtype
            if (targetType == GearType.ACCESSORY
                    && (itemType == GearType.RING || itemType == GearType.BRACELET || itemType == GearType.NECKLACE)) {
                return stack;
            }
        }
        return null;
    }

    /**
     * Adds overall percentage comparison line to the tooltip.
     */
    private void addOverallComparison(List<Component> tooltips, GearInstance hovered, GearInstance equipped) {
        if (!hovered.hasOverallValue() || !equipped.hasOverallValue()) {
            return;
        }

        float hoveredPct = hovered.getOverallPercentage();
        float equippedPct = equipped.getOverallPercentage();
        float diff = hoveredPct - equippedPct;

        String diffStr;
        String colorCode;
        if (diff > 0.5f) {
            diffStr = "+" + Math.round(diff) + "%";
            colorCode = "\u00A7a"; // green
        } else if (diff < -0.5f) {
            diffStr = Math.round(diff) + "%";
            colorCode = "\u00A7c"; // red
        } else {
            diffStr = "=";
            colorCode = "\u00A77"; // gray
        }

        String line = "\u00A77Overall: " + colorCode + diffStr
                + " \u00A77(" + Math.round(equippedPct) + "% \u2192 " + Math.round(hoveredPct) + "%)";
        tooltips.add(Component.literal(line));
    }

    /**
     * Adds per-stat comparison lines to the tooltip.
     */
    private void addStatComparisons(List<Component> tooltips, GearInstance hovered, GearInstance equipped) {
        // Build a map of equipped stats by API name for quick lookup
        Map<String, StatActualValue> equippedStats = new LinkedHashMap<>();
        for (StatActualValue stat : equipped.identifications()) {
            equippedStats.put(stat.statType().getApiName(), stat);
        }

        // Track which equipped stats we've seen (to detect "lost" stats)
        Set<String> seenEquippedStats = new HashSet<>();

        // Compare each stat on the hovered item
        for (StatActualValue hoveredStat : hovered.identifications()) {
            String apiName = hoveredStat.statType().getApiName();
            String displayName = hoveredStat.statType().getDisplayName();
            int hoveredValue = hoveredStat.value();

            StatActualValue equippedStat = equippedStats.get(apiName);
            seenEquippedStats.add(apiName);

            if (equippedStat != null) {
                int equippedValue = equippedStat.value();
                int diff = hoveredValue - equippedValue;

                String diffStr;
                String colorCode;
                if (diff > 0) {
                    diffStr = "+" + diff;
                    colorCode = isStatInverted(hoveredStat.statType()) ? "\u00A7c" : "\u00A7a";
                } else if (diff < 0) {
                    diffStr = String.valueOf(diff);
                    colorCode = isStatInverted(hoveredStat.statType()) ? "\u00A7a" : "\u00A7c";
                } else {
                    diffStr = "=";
                    colorCode = "\u00A77"; // gray
                }

                if (diff == 0) {
                    tooltips.add(Component.literal(
                            "\u00A77" + displayName + ": " + colorCode + diffStr
                                    + " \u00A77(" + equippedValue + ")"));
                } else {
                    tooltips.add(Component.literal(
                            "\u00A77" + displayName + ": " + colorCode + diffStr
                                    + " \u00A77(" + equippedValue + " \u2192 " + hoveredValue + ")"));
                }
            } else {
                // Stat is new (not on equipped item)
                String colorCode = isStatPositive(hoveredStat) ? "\u00A7a" : "\u00A7c";
                tooltips.add(Component.literal(
                        "\u00A77" + displayName + ": " + colorCode + "+" + hoveredValue + " (new)"));
            }
        }

        // Show stats that are on the equipped item but not on the hovered item ("lost")
        for (StatActualValue equippedStat : equipped.identifications()) {
            String apiName = equippedStat.statType().getApiName();
            if (!seenEquippedStats.contains(apiName)) {
                String displayName = equippedStat.statType().getDisplayName();
                int equippedValue = equippedStat.value();
                String colorCode = isStatPositive(equippedStat) ? "\u00A7c" : "\u00A7a";
                tooltips.add(Component.literal(
                        "\u00A77" + displayName + ": " + colorCode + "-" + equippedValue + " (lost)"));
            }
        }
    }

    /**
     * Returns true if higher values for this stat type are considered worse
     * (e.g., negative stats like cost or damage penalties).
     */
    private boolean isStatInverted(StatType statType) {
        return statType.displayAsInverted();
    }

    /**
     * Returns true if the stat's actual value is "positive" (beneficial to the player).
     * For normal stats, positive value = good. For inverted stats, negative value = good.
     */
    private boolean isStatPositive(StatActualValue stat) {
        if (stat.statType().displayAsInverted()) {
            return stat.value() < 0;
        }
        return stat.value() > 0;
    }
}
