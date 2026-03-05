package com.wynnsort.util;

import com.wynntils.core.components.Models;
import com.wynntils.core.persisted.storage.Storage;
import com.wynntils.models.gear.type.GearTier;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Isolated helper for accessing Wynntils' LootChest model data.
 * Models.LootChest may not exist in all Wynntils versions, so this class
 * is loaded via try/catch to avoid crashing the caller.
 */
public final class LootChestDataHelper {
    private LootChestDataHelper() {}

    private static Field dryItemTiersField;
    private static boolean fieldLookupDone = false;

    public static int getDryChestCount() {
        return Models.LootChest.getDryCount();
    }

    public static int getDryBoxCount() {
        return Models.LootChest.getDryBoxes();
    }

    public static int getOpenedChestCount() {
        return Models.LootChest.getOpenedChestCount();
    }

    @SuppressWarnings("unchecked")
    public static int getDryItemCount() {
        try {
            if (!fieldLookupDone) {
                fieldLookupDone = true;
                dryItemTiersField = Models.LootChest.getClass().getDeclaredField("dryItemTiers");
                dryItemTiersField.setAccessible(true);
            }
            if (dryItemTiersField == null) return -1;

            Storage<Map<GearTier, Integer>> storage =
                    (Storage<Map<GearTier, Integer>>) dryItemTiersField.get(Models.LootChest);
            Map<GearTier, Integer> tiers = storage.get();
            if (tiers == null) return 0;

            int total = 0;
            for (Integer count : tiers.values()) {
                if (count != null) total += count;
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }
}
