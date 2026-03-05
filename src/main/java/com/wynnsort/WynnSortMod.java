package com.wynnsort;

import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.feature.DryStreakTracker;
import com.wynnsort.feature.LootrunBeaconTracker;
import com.wynnsort.feature.LootrunSessionStats;
import com.wynnsort.feature.TradeMarketLogger;
import com.wynnsort.history.TransactionRecord;
import com.wynnsort.history.TransactionStore;
import com.wynnsort.lootrun.LootrunStore;
import com.wynnsort.market.CrowdsourceCollector;
import com.wynnsort.market.MarketPriceStore;
import com.wynnsort.market.PriceHistoryStore;
import com.wynnsort.market.SearchPresetStore;
import com.wynnsort.screen.LootrunHistoryScreen;
import com.wynnsort.screen.TransactionHistoryScreen;
import com.wynnsort.screen.TransactionListWidget;
import com.wynnsort.screen.WynnSortConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import com.wynnsort.screen.DiagnosticScreen;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.PersistentLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class WynnSortMod implements ClientModInitializer {

    public static final String MOD_ID = "wynnsort";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Log to both SLF4J (latest.log) and persistent file (wynnsort.log). */
    public static void log(String message, Object... args) {
        PersistentLog.info(message, args);
    }

    public static void logWarn(String message, Object... args) {
        LOGGER.warn(message, args);
        PersistentLog.warn(message, args);
    }

    public static void logError(String message, Object... args) {
        LOGGER.error(message, args);
        PersistentLog.error(message, args);

        // Also emit a diagnostic error event
        try {
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("message", message);
            // Check if last arg is a Throwable
            if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable t) {
                errorData.put("class", t.getClass().getSimpleName());
                errorData.put("detail", t.getMessage() != null ? t.getMessage() : "null");
            }
            DiagnosticLog.event(DiagnosticLog.Category.ERROR, "exception", errorData);
        } catch (Exception ignored) {}
    }

    private static KeyMapping toggleOverlayKey;
    private static KeyMapping openHistoryKey;
    private static KeyMapping openConfigKey;
    private static KeyMapping openLootrunHistoryKey;
    private static KeyMapping openDiagnosticsKey;
    private static KeyMapping cyclePresetsKey;

    @Override
    public void onInitializeClient() {
        PersistentLog.init();
        DiagnosticLog.init();
        log("[WS:Init] WynnSort initializing...");

        WynnSortConfig.load();
        DiagnosticLog.event(DiagnosticLog.Category.STARTUP, "mod_init", Map.of("version", "1.0.0"));

        // Log config state to diagnostics
        {
            Map<String, Object> configMap = new LinkedHashMap<>();
            configMap.put("overlayEnabled", WynnSortConfig.INSTANCE.overlayEnabled);
            configMap.put("sortButtonEnabled", WynnSortConfig.INSTANCE.sortButtonEnabled);
            configMap.put("lootrunHudEnabled", WynnSortConfig.INSTANCE.lootrunHudEnabled);
            configMap.put("tradeHistoryEnabled", WynnSortConfig.INSTANCE.tradeHistoryEnabled);
            configMap.put("marketPriceCacheEnabled", WynnSortConfig.INSTANCE.marketPriceCacheEnabled);
            configMap.put("diagnosticLoggingEnabled", WynnSortConfig.INSTANCE.diagnosticLoggingEnabled);
            DiagnosticLog.event(DiagnosticLog.Category.CONFIG, "config_loaded", configMap);
        }

        toggleOverlayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wynnsort.toggle_overlay",
                GLFW.GLFW_KEY_J,
                "category.wynnsort"
        ));

        openHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wynnsort.open_history",
                GLFW.GLFW_KEY_H,
                "category.wynnsort"
        ));

        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wynnsort.open_config",
                GLFW.GLFW_KEY_SEMICOLON,
                "category.wynnsort"
        ));

        openLootrunHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wynnsort.open_lootrun_history",
                GLFW.GLFW_KEY_L,
                "category.wynnsort"
        ));

        openDiagnosticsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wynnsort.open_diagnostics",
                GLFW.GLFW_KEY_F8,
                "category.wynnsort"
        ));

        cyclePresetsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wynnsort.cycle_presets",
                GLFW.GLFW_KEY_P,
                "category.wynnsort"
        ));

        try {
            TransactionStore.load();
        } catch (Exception e) {
            logError("[WynnSort] Failed to load transaction store", e);
        }

        try {
            MarketPriceStore.load();
        } catch (Exception e) {
            logError("[WynnSort] Failed to load market price store", e);
        }

        try {
            PriceHistoryStore.load();
        } catch (Exception e) {
            logError("[WynnSort] Failed to load price history store", e);
        }

        try {
            LootrunStore.load();
        } catch (Exception e) {
            logError("[WynnSort] Failed to load lootrun history store", e);
        }

        try {
            SearchPresetStore.load();
        } catch (Exception e) {
            logError("[WynnSort] Failed to load search preset store", e);
        }

        try {
            CrowdsourceCollector.INSTANCE.init();
        } catch (Exception e) {
            logError("[WynnSort] Failed to initialize crowdsource collector", e);
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleOverlayKey.consumeClick()) {
                onToggleOverlay(client);
            }
            while (openHistoryKey.consumeClick()) {
                try {
                    client.setScreen(new TransactionHistoryScreen());
                } catch (Exception e) {
                    logError("[WynnSort] Failed to open history screen", e);
                }
            }
            while (openConfigKey.consumeClick()) {
                try {
                    client.setScreen(new WynnSortConfigScreen(null));
                } catch (Exception e) {
                    logError("[WynnSort] Failed to open config screen", e);
                }
            }
            while (openLootrunHistoryKey.consumeClick()) {
                try {
                    client.setScreen(new LootrunHistoryScreen());
                } catch (Exception e) {
                    logError("[WynnSort] Failed to open lootrun history screen", e);
                }
            }
            while (openDiagnosticsKey.consumeClick()) {
                try {
                    client.setScreen(new DiagnosticScreen());
                } catch (Exception e) {
                    logError("[WynnSort] Failed to open diagnostics screen", e);
                }
            }
            // Consume preset cycling clicks — actual cycling is handled by ContainerScreenMixin.keyPressed()
            while (cyclePresetsKey.consumeClick()) {
                // Handled in-screen by the mixin; consume here to prevent queue buildup
            }
        });

        // Register lootrun beacon HUD overlay
        HudRenderCallback.EVENT.register(LootrunBeaconTracker.INSTANCE);

        // Register lootrun session stats HUD overlay
        HudRenderCallback.EVENT.register(LootrunSessionStats.INSTANCE);

        // Initialize and register dry streak tracker HUD overlay
        try {
            DryStreakTracker.INSTANCE.init();
            HudRenderCallback.EVENT.register(DryStreakTracker.INSTANCE);
        } catch (Exception e) {
            logError("[WynnSort] Failed to initialize dry streak tracker", e);
        }

        // Register screen overlays — sell price + market price HUD
        ScreenEvents.AFTER_INIT.register((client2, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterRender(screen).register((screen2, guiGraphics, mouseX, mouseY, tickDelta) -> {
                Minecraft mc = Minecraft.getInstance();
                int y = 4;

                // Sell screen overlay — shows buy price when selling a previously bought item
                TransactionRecord match = TradeMarketLogger.getMatchedBuyRecord();
                if (match != null && match.priceEmeralds > 0) {
                    int taxPercent = WynnSortConfig.INSTANCE.tradeMarketBuyTaxPercent;
                    long buyPrice = match.priceEmeralds;
                    if (taxPercent > 0) {
                        buyPrice = buyPrice + (buyPrice * taxPercent / 100);
                    }
                    String text = "Bought for: " + TransactionListWidget.Entry.formatPrice(buyPrice);
                    int textWidth = mc.font.width(text);
                    int x = (screen2.width - textWidth) / 2;
                    guiGraphics.fill(x - 4, y - 2, x + textWidth + 4, y + 11, 0xCC000000);
                    guiGraphics.drawString(mc.font, text, x, y, 0xFF55FF55);
                    y += 16;
                }

                // Market price overlay — shows cached price for hovered item
                if (WynnSortConfig.INSTANCE.marketPriceCacheEnabled
                        && screen2 instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen) {
                    net.minecraft.world.inventory.Slot hoveredSlot = containerScreen.hoveredSlot;
                    if (hoveredSlot != null && hoveredSlot.hasItem()) {
                        String baseName = com.wynnsort.util.ItemNameHelper.extractBaseName(hoveredSlot.getItem());
                        if (baseName != null) {
                            com.wynnsort.market.MarketPriceEntry entry =
                                    com.wynnsort.market.MarketPriceStore.getPrice(baseName);
                            if (entry != null) {
                                String priceStr = com.wynnsort.feature.MarketPriceFeature.formatEmeralds(entry.price);
                                String ageStr = com.wynnsort.feature.MarketPriceFeature.formatAge(
                                        System.currentTimeMillis() - entry.timestamp);
                                String text = "Market: " + priceStr + " (" + ageStr + " ago)";
                                int textWidth = mc.font.width(text);
                                int x = (screen2.width - textWidth) / 2;
                                guiGraphics.fill(x - 4, y - 2, x + textWidth + 4, y + 11, 0xCC000000);
                                guiGraphics.drawString(mc.font, text, x, y, 0xFFFFFF55);
                            }
                        }
                    }
                }
            });
        });

        log("[WS:Init] WynnSort initialized successfully");
    }

    private void onToggleOverlay(Minecraft client) {
        WynnSortConfig.INSTANCE.overlayEnabled = !WynnSortConfig.INSTANCE.overlayEnabled;
        WynnSortConfig.save();

        String state = WynnSortConfig.INSTANCE.overlayEnabled ? "ON" : "OFF";
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("[WynnSort] Overlay: " + state), true);
        }

        // Re-init the current screen so the stat input appears/disappears
        if (client.screen != null) {
            client.screen.resize(client, client.getWindow().getGuiScaledWidth(),
                    client.getWindow().getGuiScaledHeight());
        }
    }
}
