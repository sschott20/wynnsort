package com.wynnsort.screen;

import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.screen.HudElementSettingsScreen.ToggleEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

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

    // Scrolling
    private int scrollOffset = 0;
    private int contentHeight = 0;

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
        int y = 30 - scrollOffset;

        // --- Quality Overlay ---
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Quality Overlay", WynnSortConfig.INSTANCE.overlayEnabled,
                v -> WynnSortConfig.INSTANCE.overlayEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Show Percentage Text", WynnSortConfig.INSTANCE.showPercentageText,
                v -> WynnSortConfig.INSTANCE.showPercentageText = v, centerX, y, btnW, btnH);
        y += spacing;

        // Selected scale - read-only display, changed via trade market screen buttons
        String scaleName = WynnSortConfig.INSTANCE.selectedScale;
        if (scaleName == null || scaleName.isEmpty()) scaleName = "Overall";
        addRenderableWidget(Button.builder(
                Component.literal("Scale: " + scaleName),
                b -> {
                    // Cycle between Overall and keeping current (reset to Overall)
                    WynnSortConfig.INSTANCE.selectedScale = "Overall";
                    rebuildWidgets();
                }).bounds(centerX - btnW / 2, y, btnW, btnH).build());
        y += spacing;

        // --- Trade Market ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Sort Button", WynnSortConfig.INSTANCE.sortButtonEnabled,
                v -> WynnSortConfig.INSTANCE.sortButtonEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Search Presets", WynnSortConfig.INSTANCE.searchPresetsEnabled,
                v -> WynnSortConfig.INSTANCE.searchPresetsEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        // --- Lootrun ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        // Edit HUD Layout button (opens drag-and-drop screen)
        addRenderableWidget(Button.builder(Component.literal("Edit HUD Layout..."), b -> {
            minecraft.setScreen(new HudPositionScreen(this));
        }).bounds(centerX - btnW / 2, y, btnW, btnH).build());
        y += spacing;

        // Beacon HUD toggle + settings button
        addToggleWithSettings("Beacon HUD", WynnSortConfig.INSTANCE.lootrunHudEnabled,
                v -> WynnSortConfig.INSTANCE.lootrunHudEnabled = v, centerX, y, btnW, btnH,
                "Beacon Display Settings", List.of(
                        new ToggleEntry("Orange Countdowns", () -> WynnSortConfig.INSTANCE.showBeaconOrange,
                                v -> WynnSortConfig.INSTANCE.showBeaconOrange = v),
                        new ToggleEntry("Rainbow Countdown", () -> WynnSortConfig.INSTANCE.showBeaconRainbow,
                                v -> WynnSortConfig.INSTANCE.showBeaconRainbow = v),
                        new ToggleEntry("Grey Missions", () -> WynnSortConfig.INSTANCE.showBeaconGrey,
                                v -> WynnSortConfig.INSTANCE.showBeaconGrey = v),
                        new ToggleEntry("Crimson Trials", () -> WynnSortConfig.INSTANCE.showBeaconCrimson,
                                v -> WynnSortConfig.INSTANCE.showBeaconCrimson = v)
                ));
        y += spacing;

        // Stats HUD toggle + settings button
        addToggleWithSettings("Stats HUD", WynnSortConfig.INSTANCE.lootrunStatsHudEnabled,
                v -> WynnSortConfig.INSTANCE.lootrunStatsHudEnabled = v, centerX, y, btnW, btnH,
                "Stats Display Settings", List.of(
                        new ToggleEntry("Challenges", () -> WynnSortConfig.INSTANCE.showStatsChallenges,
                                v -> WynnSortConfig.INSTANCE.showStatsChallenges = v),
                        new ToggleEntry("Pulls / Rerolls", () -> WynnSortConfig.INSTANCE.showStatsPullsRerolls,
                                v -> WynnSortConfig.INSTANCE.showStatsPullsRerolls = v),
                        new ToggleEntry("Mythic Chance", () -> WynnSortConfig.INSTANCE.showStatsMythicChance,
                                v -> WynnSortConfig.INSTANCE.showStatsMythicChance = v),
                        new ToggleEntry("Sacrifices", () -> WynnSortConfig.INSTANCE.showStatsSacrifices,
                                v -> WynnSortConfig.INSTANCE.showStatsSacrifices = v),
                        new ToggleEntry("Beacon Summary", () -> WynnSortConfig.INSTANCE.showStatsBeaconSummary,
                                v -> WynnSortConfig.INSTANCE.showStatsBeaconSummary = v),
                        new ToggleEntry("Duration", () -> WynnSortConfig.INSTANCE.showStatsDuration,
                                v -> WynnSortConfig.INSTANCE.showStatsDuration = v),
                        new ToggleEntry("Mythic Stats", () -> WynnSortConfig.INSTANCE.showStatsMythicStats,
                                v -> WynnSortConfig.INSTANCE.showStatsMythicStats = v)
                ));
        y += spacing;

        addToggle("Lootrun History", WynnSortConfig.INSTANCE.lootrunHistoryEnabled,
                v -> WynnSortConfig.INSTANCE.lootrunHistoryEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        // Dry Streak toggle + settings button
        addToggleWithSettings("Dry Streak", WynnSortConfig.INSTANCE.dryStreakEnabled,
                v -> WynnSortConfig.INSTANCE.dryStreakEnabled = v, centerX, y, btnW, btnH,
                "Dry Streak Display Settings", List.of(
                        new ToggleEntry("Dry Chests / Items", () -> WynnSortConfig.INSTANCE.showDryChests,
                                v -> WynnSortConfig.INSTANCE.showDryChests = v),
                        new ToggleEntry("Dry Pulls", () -> WynnSortConfig.INSTANCE.showDryPulls,
                                v -> WynnSortConfig.INSTANCE.showDryPulls = v)
                ));
        y += spacing;

        // --- Market & Pricing ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Market Price Cache", WynnSortConfig.INSTANCE.marketPriceCacheEnabled,
                v -> WynnSortConfig.INSTANCE.marketPriceCacheEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Price History Tracking", WynnSortConfig.INSTANCE.priceHistoryEnabled,
                v -> WynnSortConfig.INSTANCE.priceHistoryEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        // --- Crowdsource Data ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Crowdsource Data", WynnSortConfig.INSTANCE.crowdsourceEnabled,
                v -> WynnSortConfig.INSTANCE.crowdsourceEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        // --- Trade History & Logging ---
        y += 4;
        headerPositions.add(new int[]{left, y});
        y += 14;

        addToggle("Trade History Logging", WynnSortConfig.INSTANCE.tradeHistoryEnabled,
                v -> WynnSortConfig.INSTANCE.tradeHistoryEnabled = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Trade Market Verbose Log", WynnSortConfig.INSTANCE.tradeMarketLogging,
                v -> WynnSortConfig.INSTANCE.tradeMarketLogging = v, centerX, y, btnW, btnH);
        y += spacing;

        addToggle("Diagnostic Logging", WynnSortConfig.INSTANCE.diagnosticLoggingEnabled,
                v -> WynnSortConfig.INSTANCE.diagnosticLoggingEnabled = v, centerX, y, btnW, btnH);
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
        y += spacing;

        // Track total content height for scroll clamping
        contentHeight = y + scrollOffset;

        // Done button — always at bottom, never scrolls
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

    private void addToggleWithSettings(String label, boolean currentValue, java.util.function.Consumer<Boolean> setter,
                                       int centerX, int y, int totalWidth, int height,
                                       String settingsTitle, List<ToggleEntry> settingsToggles) {
        int settingsBtnW = 26;
        int gap = 2;
        int toggleW = totalWidth - settingsBtnW - gap;

        String stateText = currentValue ? "ON" : "OFF";
        int toggleX = centerX - totalWidth / 2;

        addRenderableWidget(Button.builder(Component.literal(label + ": " + stateText), b -> {
            boolean newVal = !stateText.equals("ON");
            setter.accept(newVal);
            rebuildWidgets();
        }).bounds(toggleX, y, toggleW, height).build());

        addRenderableWidget(Button.builder(Component.literal("..."), b -> {
            minecraft.setScreen(new HudElementSettingsScreen(this, settingsTitle, settingsToggles));
        }).bounds(toggleX + toggleW + gap, y, settingsBtnW, height).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, contentHeight - (this.height - 40));
        scrollOffset = Mth.clamp(scrollOffset - (int) (verticalAmount * 16), 0, maxScroll);
        rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // Title (fixed, doesn't scroll)
        guiGraphics.drawCenteredString(this.font, this.title, centerX, 10, 0xFFFFFF);

        // Section headers
        String[] headers = {"Quality Overlay", "Trade Market", "Lootrun", "Market & Pricing", "Crowdsource Data", "Trade History & Logging"};
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

        // Scroll indicator
        int maxScroll = Math.max(0, contentHeight - (this.height - 40));
        if (maxScroll > 0) {
            String scrollHint = scrollOffset < maxScroll ? "Scroll for more..." : "Scroll up for more...";
            guiGraphics.drawCenteredString(this.font, scrollHint, centerX, this.height - 42, 0xFF888888);
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
