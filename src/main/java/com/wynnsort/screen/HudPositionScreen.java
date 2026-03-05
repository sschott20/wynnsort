package com.wynnsort.screen;

import com.wynnsort.config.WynnSortConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Drag-and-drop screen for repositioning HUD elements.
 * Shows preview rectangles for each HUD panel that can be dragged to new positions.
 * Positions are stored as fractional screen coordinates (0.0-1.0) in config.
 */
public class HudPositionScreen extends Screen {

    private static final int BEACON_W = 160, BEACON_H = 80;
    private static final int STATS_W = 164, STATS_H = 140;
    private static final int DRY_W = 140, DRY_H = 36;

    private static final int COLOR_BEACON = 0xFFFFAA00;
    private static final int COLOR_STATS = 0xFF55FF55;
    private static final int COLOR_DRY = 0xFFFF5555;

    private final Screen parent;

    // Current pixel positions (converted from/to fractional on init/save)
    private int beaconX, beaconY;
    private int statsX, statsY;
    private int dryX, dryY;

    // Drag state
    private int dragIndex = -1; // -1=none, 0=beacon, 1=stats, 2=dry
    private int dragOffsetX, dragOffsetY;

    public HudPositionScreen(Screen parent) {
        super(Component.literal("Edit HUD Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        WynnSortConfig cfg = WynnSortConfig.INSTANCE;

        // Convert fractional positions to pixels, or use defaults
        beaconX = cfg.beaconHudX >= 0 ? (int) (cfg.beaconHudX * width) : 4;
        beaconY = cfg.beaconHudY >= 0 ? (int) (cfg.beaconHudY * height) : (height - BEACON_H) / 2;

        statsX = cfg.sessionStatsHudX >= 0 ? (int) (cfg.sessionStatsHudX * width) : width - STATS_W - 4;
        statsY = cfg.sessionStatsHudY >= 0 ? (int) (cfg.sessionStatsHudY * height) : 4;

        dryX = cfg.dryStreakHudX >= 0 ? (int) (cfg.dryStreakHudX * width) : width - DRY_W - 4;
        dryY = cfg.dryStreakHudY >= 0 ? (int) (cfg.dryStreakHudY * height) : height - DRY_H - 50;

        clampPositions();

        addRenderableWidget(Button.builder(Component.literal("Reset All"), b -> {
            cfg.beaconHudX = -1;
            cfg.beaconHudY = -1;
            cfg.sessionStatsHudX = -1;
            cfg.sessionStatsHudY = -1;
            cfg.dryStreakHudX = -1;
            cfg.dryStreakHudY = -1;
            rebuildWidgets();
        }).bounds(width / 2 - 104, height - 28, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
                .bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);

        gg.drawCenteredString(this.font, this.title, width / 2, 6, 0xFFFFFF);
        gg.drawCenteredString(this.font, "Click and drag to reposition", width / 2, 18, 0xFF888888);

        drawHudPreview(gg, beaconX, beaconY, BEACON_W, BEACON_H,
                "Beacon Tracker", COLOR_BEACON, dragIndex == 0);
        drawHudPreview(gg, statsX, statsY, STATS_W, STATS_H,
                "Session Stats", COLOR_STATS, dragIndex == 1);
        drawHudPreview(gg, dryX, dryY, DRY_W, DRY_H,
                "Dry Streak", COLOR_DRY, dragIndex == 2);
    }

    private void drawHudPreview(GuiGraphics gg, int x, int y, int w, int h,
                                String label, int color, boolean selected) {
        // Background
        gg.fill(x, y, x + w, y + h, 0xA0000000);

        // Border
        int borderColor = selected ? 0xFFFFFFFF : (color & 0x00FFFFFF) | 0xCC000000;
        gg.fill(x, y, x + w, y + 1, borderColor);
        gg.fill(x, y + h - 1, x + w, y + h, borderColor);
        gg.fill(x, y, x + 1, y + h, borderColor);
        gg.fill(x + w - 1, y, x + w, y + h, borderColor);

        // Label centered
        gg.drawCenteredString(this.font, label, x + w / 2, y + h / 2 - 4, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Check in reverse draw order so topmost wins
            if (isInside(mouseX, mouseY, dryX, dryY, DRY_W, DRY_H)) {
                startDrag(2, mouseX, mouseY, dryX, dryY);
                return true;
            }
            if (isInside(mouseX, mouseY, statsX, statsY, STATS_W, STATS_H)) {
                startDrag(1, mouseX, mouseY, statsX, statsY);
                return true;
            }
            if (isInside(mouseX, mouseY, beaconX, beaconY, BEACON_W, BEACON_H)) {
                startDrag(0, mouseX, mouseY, beaconX, beaconY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void startDrag(int index, double mouseX, double mouseY, int elemX, int elemY) {
        dragIndex = index;
        dragOffsetX = (int) mouseX - elemX;
        dragOffsetY = (int) mouseY - elemY;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragIndex >= 0 && button == 0) {
            int newX = (int) mouseX - dragOffsetX;
            int newY = (int) mouseY - dragOffsetY;
            switch (dragIndex) {
                case 0 -> { beaconX = newX; beaconY = newY; }
                case 1 -> { statsX = newX; statsY = newY; }
                case 2 -> { dryX = newX; dryY = newY; }
            }
            clampPositions();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragIndex >= 0) {
            dragIndex = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void clampPositions() {
        beaconX = Math.max(0, Math.min(beaconX, width - BEACON_W));
        beaconY = Math.max(0, Math.min(beaconY, height - BEACON_H));
        statsX = Math.max(0, Math.min(statsX, width - STATS_W));
        statsY = Math.max(0, Math.min(statsY, height - STATS_H));
        dryX = Math.max(0, Math.min(dryX, width - DRY_W));
        dryY = Math.max(0, Math.min(dryY, height - DRY_H));
    }

    private void saveAndClose() {
        WynnSortConfig cfg = WynnSortConfig.INSTANCE;
        cfg.beaconHudX = (float) beaconX / width;
        cfg.beaconHudY = (float) beaconY / height;
        cfg.sessionStatsHudX = (float) statsX / width;
        cfg.sessionStatsHudY = (float) statsY / height;
        cfg.dryStreakHudX = (float) dryX / width;
        cfg.dryStreakHudY = (float) dryY / height;
        WynnSortConfig.save();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        saveAndClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
