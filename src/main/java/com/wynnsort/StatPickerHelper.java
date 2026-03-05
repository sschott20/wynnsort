package com.wynnsort;

import com.wynntils.core.components.Models;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.stats.type.StatActualValue;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scans container items and collects all unique stat display names
 * for the clickable stat picker buttons.
 */
public final class StatPickerHelper {

    private StatPickerHelper() {}

    public static List<String> getAvailableStats(AbstractContainerScreen<?> screen) {
        Set<String> stats = new LinkedHashSet<>();

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isEmpty()) continue;
            if (!(opt.get() instanceof GearItem gearItem)) continue;

            Optional<GearInstance> instOpt = gearItem.getItemInstance();
            if (instOpt.isEmpty()) continue;

            for (StatActualValue actual : instOpt.get().identifications()) {
                stats.add(actual.statType().getDisplayName());
            }
        }

        List<String> sorted = new ArrayList<>(stats);
        Collections.sort(sorted);
        return sorted;
    }
}
