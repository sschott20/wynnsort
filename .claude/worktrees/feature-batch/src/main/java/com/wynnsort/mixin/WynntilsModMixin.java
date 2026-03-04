package com.wynnsort.mixin;

import com.wynnsort.WynnSortMod;
import com.wynnsort.feature.DryStreakTracker;
import com.wynnsort.feature.MarketPriceFeature;
import com.wynnsort.feature.QualityOverlayFeature;
import com.wynnsort.feature.TradeMarketLogger;
import com.wynntils.core.WynntilsMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(value = WynntilsMod.class, remap = false)
public class WynntilsModMixin {

    @Inject(method = "init", at = @At("RETURN"))
    private static void wynnsort$onInitReturn(
            WynntilsMod.ModLoader loader,
            String modVersion,
            boolean isDevelopmentEnvironment,
            File modFile,
            CallbackInfo ci
    ) {
        WynntilsMod.registerEventListener(QualityOverlayFeature.INSTANCE);
        WynntilsMod.registerEventListener(TradeMarketLogger.INSTANCE);
        WynntilsMod.registerEventListener(MarketPriceFeature.INSTANCE);
        WynntilsMod.registerEventListener(DryStreakTracker.INSTANCE);
        WynnSortMod.log("WynnSort registered on the Wynntils event bus");
    }
}
