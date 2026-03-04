package com.wynnsort.screen;

import com.wynnsort.config.WynnSortConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class WynnSortConfigScreen extends Screen {

    private static final int COLOR_HEADER = 0xFFFFAA00;
    private static final int COLOR_LABEL = 0xFFAAAAAA;

    private final Screen parent;
    private EditBox minPriceBox;
    private EditBox taxPercentBox;

    // Tracked positions for render()
    private final List<int[]> headerPositions = new ArrayList<>();
    private final List<int[]> labelPositions = new ArrayList<>();

    public WynnSortConfigScreen(Screen parent) {
        super(Component.literal("WynnSort Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        headerPositions.clear();
        labelPositions.clear();

        int centerX = this.width / 2;
        int left = centerX - 100;
        int btnW = 200;
        int btnH = 20;
        int spacing = 24;
        int y = 30;

        // --- Quality Overlay ---
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Quality Overlay", WynnSortConfig.INSTANCE.overlayEnabled,
                v -> WynnSortConfig.INSTANCE.overlayEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Show Percentage Text", WynnSortConfig.INSTANCE.showPercentageText,
                v -> WynnSortConfig.INSTANCE.showPercentageText = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Weighted Scale (Nori/Wynnpool)", WynnSortConfig.INSTANCE.useWeightedScale,
                v -> WynnSortConfig.INSTANCE.useWeightedScale = v, centerX, y, btnW, btnH);
        y += spacing;

        // --- Trade Market ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Sort Button", WynnSortConfig.INSTANCE.sortButtonEnabled,
                v -> WynnSortConfig.INSTANCE.sortButtonEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Auto-Sort Cheapest", WynnSortConfig.INSTANCE.autoSortCheapest,
                v -> WynnSortConfig.INSTANCE.autoSortCheapest = v, centerX, y, btnW, btnH);
        y += spacing;

        // --- Lootrun ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Lootrun Beacon HUD", WynnSortConfig.INSTANCE.lootrunHudEnabled,
                v -> WynnSortConfig.INSTANCE.lootrunHudEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Dry Streak Tracker", WynnSortConfig.INSTANCE.dryStreakEnabled,
                v -> WynnSortConfig.INSTANCE.dryStreakEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        // --- Market Price Cache ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Market Price Cache", WynnSortConfig.INSTANCE.marketPriceCacheEnabled,
                v -> WynnSortConfig.INSTANCE.marketPriceCacheEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        // --- Trade History ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Trade History Logging", WynnSortConfig.INSTANCE.tradeHistoryEnabled,
                v -> WynnSortConfig.INSTANCE.tradeHistoryEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Trade Market Verbose Log", WynnSortConfig.INSTANCE.tradeMarketLogging,
                v -> WynnSortConfig.INSTANCE.tradeMarketLogging = v, centerX, y, btnW, btnH);
        y += spacing;

        // Min price filter — label left, EditBox right
        int labelW = this.font.width("Min Price Filter: ");
        int boxX = left + labelW;
        int boxW = btnW - labelW;
        labelPositions.add(new int[]{left, y + 6});

        minPriceBox = new EditBox(this.font, boxX, y, boxW, btnH, Component.literal("Min Price"));
        minPriceBox.setMaxLength(12);
        minPriceBox.setValue(String.valueOf(WynnSortConfig.INSTANCE.tradeHistoryMinPriceFilter));
        minPriceBox.setResponder(val -> {
            try {
                WynnSortConfig.INSTANCE.tradeHistoryMinPriceFilter = val.isEmpty() ? 0 : Long.parseLong(val.replace(",", ""));
            } catch (NumberFormatException ignored) {}
        });
        addRenderableWidget(minPriceBox);
        y += spacing;

        // Tax percent
        int taxLabelW = this.font.width("Buy Tax %: ");
        int taxBoxX = left + taxLabelW;
        int taxBoxW = btnW - taxLabelW;
        labelPositions.add(new int[]{left, y + 6});

        taxPercentBox = new EditBox(this.font, taxBoxX, y, taxBoxW, btnH, Component.literal("Tax %"));
        taxPercentBox.setMaxLength(3);
        taxPercentBox.setValue(String.valueOf(WynnSortConfig.INSTANCE.tradeMarketBuyTaxPercent));
        taxPercentBox.setResponder(val -> {
            try {
                WynnSortConfig.INSTANCE.tradeMarketBuyTaxPercent = val.isEmpty() ? 0 : Integer.parseInt(val);
            } catch (NumberFormatException ignored) {}
        });
        addRenderableWidget(taxPercentBox);

        // Done button
        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> {
            WynnSortConfig.save();
            minecraft.setScreen(parent);
        }).bounds(centerX - 100, this.height - 28, 200, 20).build());
    }

    private void addToggle(String label, boolean currentValue, java.util.function.Consumer<Boolean> setter,
                           int centerX, int y, int width, int height) {
        String stateText = currentValue ? "ON" : "OFF";
        Button btn = Button.builder(Component.literal(label + ": " + stateText), b -> {
            boolean newVal = !stateText.equals("ON");
            setter.accept(newVal);
            rebuildWidgets();
        }).bounds(centerX - width / 2, y, width, height).build();
        addRenderableWidget(btn);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // Title
        guiGraphics.drawCenteredString(this.font, this.title, centerX, 10, 0xFFFFFF);

        // Section headers
        String[] headers = {"Quality Overlay", "Trade Market", "Lootrun", "Market Price Cache", "Trade History"};
        for (int i = 0; i < headerPositions.size() && i < headers.length; i++) {
            int[] pos = headerPositions.get(i);
            guiGraphics.drawString(this.font, headers[i], pos[0], pos[1], COLOR_HEADER);
        }

        // EditBox labels
        String[] labels = {"Min Price Filter:", "Buy Tax %:"};
        for (int i = 0; i < labelPositions.size() && i < labels.length; i++) {
            int[] pos = labelPositions.get(i);
            guiGraphics.drawString(this.font, labels[i], pos[0], pos[1], COLOR_LABEL);
        }
    }

    @Override
    public void onClose() {
        WynnSortConfig.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
