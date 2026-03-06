package com.wynnsort.screen;

import com.wynnsort.util.DiagnosticEvent;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.DiagnosticLog.Category;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * In-game diagnostic log viewer screen, accessible via F8 keybind.
 *
 * Shows a scrollable list of recent diagnostic events with category filtering,
 * color coding, auto-scroll, and export capability.
 */
public class DiagnosticScreen extends Screen {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Category colors (ARGB)
    private static final int COLOR_TRADE_MARKET = 0xFF55FF55; // green
    private static final int COLOR_LOOTRUN      = 0xFF55FFFF; // cyan
    private static final int COLOR_OVERLAY       = 0xFFFFFF55; // yellow
    private static final int COLOR_BEACON        = 0xFFFF8800; // orange
    private static final int COLOR_CONFIG        = 0xFFAAAAFF; // light blue
    private static final int COLOR_STARTUP       = 0xFFFFFFFF; // white
    private static final int COLOR_ERROR         = 0xFFFF5555; // red
    private static final int COLOR_DEFAULT       = 0xFFAAAAAA; // grey

    private enum FilterMode {
        ALL, TRADE_MARKET, LOOTRUN, BEACON, ERROR
    }

    private FilterMode filterMode = FilterMode.ALL;
    private boolean autoScroll = true;
    private int scrollOffset = 0;
    private long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 500;

    private List<DiagnosticEvent> events = new ArrayList<>();

    // Layout constants
    private static final int HEADER_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 20;
    private static final int ROW_HEIGHT = 11;

    public DiagnosticScreen() {
        super(Component.literal("WynnSort Diagnostics"));
    }

    @Override
    protected void init() {
        refreshEvents();

        int btnY = 4;
        int btnH = 16;
        int btnW = 65;
        int x = 4;

        // Filter buttons
        addFilterButton("All", FilterMode.ALL, x, btnY, btnW, btnH);
        x += btnW + 2;
        addFilterButton("Trade", FilterMode.TRADE_MARKET, x, btnY, btnW, btnH);
        x += btnW + 2;
        addFilterButton("Lootrun", FilterMode.LOOTRUN, x, btnY, btnW, btnH);
        x += btnW + 2;
        addFilterButton("Beacon", FilterMode.BEACON, x, btnY, btnW, btnH);
        x += btnW + 2;
        addFilterButton("Error", FilterMode.ERROR, x, btnY, btnW, btnH);

        // Right side buttons
        int rightX = this.width - 4;

        // Export button
        int exportW = 50;
        rightX -= exportW;
        addRenderableWidget(Button.builder(Component.literal("Export"), btn -> {
            Path path = DiagnosticLog.exportEvents(events);
            if (path != null && minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("[WynnSort] Exported to: " + path.getFileName()), false);
            }
        }).bounds(rightX, btnY, exportW, btnH).build());

        // Auto-scroll toggle
        int scrollW = 70;
        rightX -= scrollW + 4;
        addRenderableWidget(Button.builder(
                Component.literal(autoScroll ? "[AutoScrl]" : "AutoScrl"), btn -> {
            autoScroll = !autoScroll;
            rebuildWidgets();
        }).bounds(rightX, btnY, scrollW, btnH).build());
    }

    private void addFilterButton(String label, FilterMode mode, int x, int y, int w, int h) {
        String text = filterMode == mode ? "[" + label + "]" : label;
        addRenderableWidget(Button.builder(Component.literal(text), btn -> {
            filterMode = mode;
            scrollOffset = 0;
            refreshEvents();
            rebuildWidgets();
        }).bounds(x, y, w, h).build());
    }

    private void refreshEvents() {
        int maxEvents = 500;
        if (filterMode == FilterMode.ALL) {
            events = DiagnosticLog.getRecentEvents(maxEvents);
        } else {
            Category cat = switch (filterMode) {
                case TRADE_MARKET -> Category.TRADE_MARKET;
                case LOOTRUN -> Category.LOOTRUN;
                case BEACON -> Category.BEACON;
                case ERROR -> Category.ERROR;
                default -> null;
            };
            if (cat != null) {
                events = DiagnosticLog.getRecentEvents(cat, maxEvents);
            } else {
                events = DiagnosticLog.getRecentEvents(maxEvents);
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Throttle refreshes to avoid per-frame lock contention
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime > REFRESH_INTERVAL_MS) {
            refreshEvents();
            lastRefreshTime = now;
        }

        int listTop = HEADER_HEIGHT;
        int listBottom = this.height - FOOTER_HEIGHT;
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;

        if (events.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "No diagnostic events recorded",
                    this.width / 2, listTop + 20, 0xFF888888);
            return;
        }

        // Auto-scroll to latest (events are newest-first, so scroll to 0)
        if (autoScroll) {
            scrollOffset = 0;
        }

        // Clamp scroll
        int maxScroll = Math.max(0, events.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        // Render visible rows
        int y = listTop;
        for (int i = scrollOffset; i < events.size() && y + ROW_HEIGHT <= listBottom; i++) {
            DiagnosticEvent evt = events.get(i);
            renderEventRow(guiGraphics, evt, y);
            y += ROW_HEIGHT;
        }

        // Footer: event count and scroll info
        String footer = String.format("%d events | Showing %d-%d | %s",
                events.size(),
                scrollOffset + 1,
                Math.min(scrollOffset + visibleRows, events.size()),
                autoScroll ? "Auto-scrolling" : "Scroll paused");
        guiGraphics.drawString(this.font, footer, 4, this.height - 14, 0xFF888888);
    }

    private void renderEventRow(GuiGraphics guiGraphics, DiagnosticEvent evt, int y) {
        int x = 4;

        // Timestamp
        String time = formatTime(evt.timestamp);
        guiGraphics.drawString(this.font, time, x, y, 0xFF888888);
        x += this.font.width(time) + 4;

        // Category (color-coded, short form)
        String cat = evt.category != null ? evt.category : "";
        int catColor = getCategoryColor(cat);
        String catShort = shortCategory(cat);
        guiGraphics.drawString(this.font, catShort, x, y, catColor);
        x += this.font.width(catShort) + 4;

        // Event type
        String evtType = evt.eventType != null ? evt.eventType : "";
        guiGraphics.drawString(this.font, evtType, x, y, 0xFFDDDDDD);
        x += this.font.width(evtType) + 6;

        // Data summary (fill remaining width)
        int remainingWidth = this.width - x - 4;
        if (remainingWidth > 30) {
            String summary = evt.dataSummary(remainingWidth / 5); // approximate chars
            guiGraphics.drawString(this.font, summary, x, y, 0xFF999999);
        }
    }

    private String formatTime(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                .format(TIME_FMT);
    }

    private String shortCategory(String category) {
        return switch (category) {
            case "TRADE_MARKET" -> "TM";
            case "LOOTRUN" -> "LR";
            case "OVERLAY" -> "OV";
            case "BEACON" -> "BC";
            case "CONFIG" -> "CF";
            case "STARTUP" -> "ST";
            case "ERROR" -> "ER";
            default -> category.substring(0, Math.min(2, category.length()));
        };
    }

    private int getCategoryColor(String category) {
        return switch (category) {
            case "TRADE_MARKET" -> COLOR_TRADE_MARKET;
            case "LOOTRUN" -> COLOR_LOOTRUN;
            case "OVERLAY" -> COLOR_OVERLAY;
            case "BEACON" -> COLOR_BEACON;
            case "CONFIG" -> COLOR_CONFIG;
            case "STARTUP" -> COLOR_STARTUP;
            case "ERROR" -> COLOR_ERROR;
            default -> COLOR_DEFAULT;
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Scrolling up means going back in time (higher offset), down means more recent
        autoScroll = false;
        scrollOffset -= (int) verticalAmount * 3;
        int listHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT;
        int visibleRows = listHeight / ROW_HEIGHT;
        int maxScroll = Math.max(0, events.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        rebuildWidgets();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
