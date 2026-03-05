package com.wynnsort.util;

import com.wynntils.core.components.Services;
import com.wynntils.models.gear.type.ItemWeightSource;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.services.itemweight.type.ItemWeighting;

import java.util.List;

/**
 * Isolated helper for weighted scoring. This class imports Wynntils Services/ItemWeight
 * APIs that may not exist in all Wynntils versions. By isolating these imports,
 * ScoreComputation can load even when these APIs are unavailable.
 */
public final class WeightedScoreHelper {
    private WeightedScoreHelper() {}

    public static float compute(GearItem gearItem) {
        String itemName = gearItem.getItemInfo().name();
        for (ItemWeightSource source : new ItemWeightSource[]{
                ItemWeightSource.WYNNPOOL, ItemWeightSource.NORI}) {
            List<ItemWeighting> weightings =
                    Services.ItemWeight.getItemWeighting(itemName, source);
            if (!weightings.isEmpty()) {
                return Services.ItemWeight.calculateWeighting(
                        weightings.get(0), gearItem);
            }
        }
        return Float.NaN;
    }
}
