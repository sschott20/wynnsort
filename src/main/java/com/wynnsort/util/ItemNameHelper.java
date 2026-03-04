package com.wynnsort.util;

import com.wynntils.core.components.Models;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.EmeraldItem;
import com.wynntils.models.items.items.game.GameItem;
import com.wynntils.models.items.items.game.GearItem;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.regex.Pattern;

public final class ItemNameHelper {

    private static final Pattern FORMATTING_CODES = Pattern.compile("\u00A7.");

    private ItemNameHelper() {}

    /**
     * Extracts the canonical base name from an ItemStack via Wynntils models.
     * For GearItem: uses itemInfo.name(). For other game items: strips formatting
     * codes and "Unidentified " prefix from hover name. Returns null for
     * non-game items, emeralds, or on error.
     */
    public static String extractBaseName(ItemStack stack) {
        try {
            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isEmpty()) return null;
            WynnItem item = opt.get();
            if (!(item instanceof GameItem)) return null;
            if (item instanceof EmeraldItem) return null;

            if (item instanceof GearItem gearItem) {
                try { return cleanItemName(gearItem.getItemInfo().name()); } catch (Exception ignored) {}
            }

            String name = stack.getHoverName().getString();
            if (name != null && !name.isEmpty()) {
                name = cleanItemName(name);
                if (name.startsWith("Unidentified ")) {
                    name = name.substring("Unidentified ".length());
                }
                return name;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Strips Minecraft formatting codes and trailing artifact characters.
     */
    public static String cleanItemName(String name) {
        if (name == null) return null;
        name = FORMATTING_CODES.matcher(name).replaceAll("");
        while (name.endsWith("\u00C0")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.trim();
    }
}
