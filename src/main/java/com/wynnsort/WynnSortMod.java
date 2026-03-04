package com.wynnsort;

import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.feature.LootrunBeaconTracker;
import com.wynnsort.feature.TradeMarketLogger;
import com.wynnsort.history.TransactionRecord;
import com.wynnsort.history.TransactionStore;
import com.wynnsort.market.MarketPriceStore;
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
import com.wynnsort.util.PersistentLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WynnSortMod implements ClientModInitializer {

    public static final String MOD_ID = "wynnsort";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Log to both SLF4J (latest.log) and persistent file (wynnsort.log). */
    public static void log(String message, Object... args) {
        LOGGER.info(message, args);
        PersistentLog.info(message, args);
    }

    public static void logWarn(String message, Object... args) {
        LOGGER.warn(message, args);
        PersistentLog.warn(message, args);
    }

    public static void logError(String message, Object... args) {
        LOGGER.error(message, args);
        PersistentLog.error(message, args);
    }

    private static KeyMapping toggleOverlayKey;
    private static KeyMapping openHistoryKey;
    private static KeyMapping openConfigKey;

    @Override
    public void onInitializeClient() {
        PersistentLog.init();
        log("WynnSort initializing...");

        WynnSortConfig.load();

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
        });

        // Register lootrun beacon HUD overlay
        HudRenderCallback.EVENT.register(LootrunBeaconTracker.INSTANCE);

        // Register sell screen overlay — shows buy price when selling a previously bought item
        ScreenEvents.AFTER_INIT.register((client2, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterRender(screen).register((screen2, guiGraphics, mouseX, mouseY, tickDelta) -> {
                TransactionRecord match = TradeMarketLogger.getMatchedBuyRecord();
                if (match == null || match.priceEmeralds <= 0) return;

                Minecraft mc = Minecraft.getInstance();
                int taxPercent = WynnSortConfig.INSTANCE.tradeMarketBuyTaxPercent;
                long buyPrice = match.priceEmeralds;
                if (taxPercent > 0) {
                    buyPrice = buyPrice + (buyPrice * taxPercent / 100);
                }
                String text = "Bought for: " + TransactionListWidget.Entry.formatPrice(buyPrice);
                int textWidth = mc.font.width(text);
                int x = (screen2.width - textWidth) / 2;
                int y = 4;

                // Background
                guiGraphics.fill(x - 4, y - 2, x + textWidth + 4, y + 11, 0xCC000000);
                guiGraphics.drawString(mc.font, text, x, y, 0xFF55FF55);
            });
        });

        log("WynnSort initialized successfully");
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
