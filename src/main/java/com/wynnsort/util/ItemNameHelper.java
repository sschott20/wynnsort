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
    /** Matches quality percentage suffixes like [100%] or [98%] appended to item names */
    private static final Pattern QUALITY_SUFFIX = Pattern.compile("\\s*\\[\\d+%]$");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private ItemNameHelper() {}

    /**
     * Extracts the canonical base name from an ItemStack via Wynntils models.
     * For GearItem: uses itemInfo.name(). For other game items: strips formatting
     * codes, "Unidentified " prefix, and quality suffixes from hover name.
     * Returns null for non-game items, emeralds, or on error.
     *
     * @param stack the item stack to extract a name from; if null, returns null
     * @return the cleaned base name, or null if the item is not a recognized game item
     */
    public static String extractBaseName(ItemStack stack) {
        if (stack == null) return null;
        try {
            Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
            if (opt.isEmpty()) return null;
            WynnItem item = opt.get();
            if (!(item instanceof GameItem)) return null;
            if (item instanceof EmeraldItem) return null;

            if (item instanceof GearItem gearItem) {
                try { return cleanItemName(gearItem.getItemInfo().name()); } catch (Exception ignored) {}
            }

            return extractFromHoverName(stack);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Extracts a base name from the item's hover name, stripping formatting codes,
     * artifact characters, "Unidentified " prefix, and quality percentage suffixes.
     */
    private static String extractFromHoverName(ItemStack stack) {
        String name = stack.getHoverName().getString();
        if (name != null && !name.isEmpty()) {
            name = cleanItemName(name);
            if (name.startsWith("Unidentified ")) {
                name = name.substring("Unidentified ".length());
            }
            return name;
        }
        return null;
    }

    /**
     * Strips Minecraft formatting codes, artifact characters (À anywhere in name),
     * and quality percentage suffixes like [100%].
     */
    public static String cleanItemName(String name) {
        if (name == null) return null;
        name = FORMATTING_CODES.matcher(name).replaceAll("");
        // Replace À characters (Wynncraft font artifacts) with spaces, then collapse
        name = name.replace('\u00C0', ' ');
        // Collapse multiple spaces into one
        name = MULTI_SPACE.matcher(name).replaceAll(" ");
        // Strip quality percentage suffixes like [100%] or [98%]
        name = QUALITY_SUFFIX.matcher(name).replaceAll("");
        return name.trim();
    }
}
