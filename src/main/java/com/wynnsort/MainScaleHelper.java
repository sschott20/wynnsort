package com.wynnsort;

import com.wynntils.core.components.Models;
import com.wynntils.models.gear.type.GearInfo;
import com.wynntils.models.gear.type.GearTier;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.stats.type.StatPossibleValues;
import com.wynntils.models.stats.type.StatType;
import com.wynntils.utils.type.Pair;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public final class MainScaleHelper {

    private MainScaleHelper() {}

    public static boolean hasMythicInContainer(AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isEmpty()) continue;
            if (opt.get() instanceof GearItem gearItem) {
                if (gearItem.getItemInfo().tier() == GearTier.MYTHIC) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String findMainScale(AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isEmpty()) continue;
            if (opt.get() instanceof GearItem gearItem) {
                GearInfo info = gearItem.getItemInfo();
                if (info.tier() == GearTier.MYTHIC) {
                    return findHighestBaseStat(info);
                }
            }
        }
        return null;
    }

    private static String findHighestBaseStat(GearInfo info) {
        String bestStat = null;
        int highestBase = 0;
        for (Pair<StatType, StatPossibleValues> pair : info.variableStats()) {
            int absBase = Math.abs(pair.b().baseValue());
            if (absBase > highestBase) {
                highestBase = absBase;
                bestStat = pair.a().getDisplayName();
            }
        }
        return bestStat;
    }
}
