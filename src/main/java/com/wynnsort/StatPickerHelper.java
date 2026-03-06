package com.wynnsort;

import com.wynntils.core.components.Models;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.stats.type.StatActualValue;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
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

    /**
     * Returns available stats from the container, excluding player inventory slots.
     */
    public static List<String> getAvailableStats(AbstractContainerScreen<?> screen) {
        return getAvailableStats(screen, null);
    }

    /**
     * Returns available stats from the container, excluding player inventory slots.
     * If searchQuery is non-null/non-empty, only includes stats from items whose
     * name matches the search query (case-insensitive exact match preferred,
     * falls back to items whose name starts with the query).
     */
    public static List<String> getAvailableStats(AbstractContainerScreen<?> screen, String searchQuery) {
        Set<String> stats = new LinkedHashSet<>();
        String query = (searchQuery != null) ? searchQuery.trim().toLowerCase() : "";

        // First pass: collect all gear items from container slots only (skip player inventory)
        List<GearItem> candidateItems = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            // Skip player inventory and equipment slots
            if (slot.container instanceof Inventory) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isEmpty()) continue;
            if (!(opt.get() instanceof GearItem gearItem)) continue;
            if (gearItem.getItemInstance().isEmpty()) continue;

            candidateItems.add(gearItem);
        }

        // If we have a search query, filter items to only those matching the query
        List<GearItem> filteredItems;
        if (!query.isEmpty()) {
            filteredItems = filterBySearchQuery(candidateItems, query);
        } else {
            filteredItems = candidateItems;
        }

        // Collect stats from filtered items
        for (GearItem gearItem : filteredItems) {
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

    /**
     * Filters gear items to only those whose name matches the search query.
     * Priority: exact match > starts-with match > contains match.
     * If exact matches exist, only those are returned. Otherwise falls back
     * to starts-with, then contains.
     */
    private static List<GearItem> filterBySearchQuery(List<GearItem> items, String query) {
        List<GearItem> exactMatches = new ArrayList<>();
        List<GearItem> startsWithMatches = new ArrayList<>();
        List<GearItem> containsMatches = new ArrayList<>();

        for (GearItem gearItem : items) {
            String itemName = gearItem.getItemInfo().name().toLowerCase();
            if (itemName.equals(query)) {
                exactMatches.add(gearItem);
            } else if (itemName.startsWith(query)) {
                startsWithMatches.add(gearItem);
            } else if (itemName.contains(query)) {
                containsMatches.add(gearItem);
            }
        }

        // Return the most specific match tier that has results.
        // If we have exact matches, use only those (e.g., "spring" -> "Spring" not "Spring Water")
        if (!exactMatches.isEmpty()) return exactMatches;
        // If starts-with has matches, prefer those over contains
        // (e.g., query "spring" matches "Spring" via startsWith but also "Spring Water")
        // Since startsWith includes "Spring Water" too, we need a smarter approach:
        // Use the shortest-name items from startsWith matches (closest to exact match)
        if (!startsWithMatches.isEmpty()) {
            int minLen = Integer.MAX_VALUE;
            for (GearItem g : startsWithMatches) {
                minLen = Math.min(minLen, g.getItemInfo().name().length());
            }
            List<GearItem> shortest = new ArrayList<>();
            for (GearItem g : startsWithMatches) {
                if (g.getItemInfo().name().length() == minLen) {
                    shortest.add(g);
                }
            }
            return shortest;
        }
        if (!containsMatches.isEmpty()) return containsMatches;

        // No matches at all — return all items (don't filter)
        return items;
    }
}
