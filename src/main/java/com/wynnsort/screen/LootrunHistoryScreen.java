package com.wynnsort.screen;

import com.wynnsort.lootrun.LootrunRecord;
import com.wynnsort.lootrun.LootrunStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Lootrun history and analytics screen.
 * Top section: lifetime stats summary bar.
 * Middle section: scrollable list of individual run records.
 */
public class LootrunHistoryScreen extends Screen {

    private static final int COLOR_HEADER = 0xFFFFAA00;
    private static final int COLOR_STAT_LABEL = 0xFFAAAAAA;
    private static final int COLOR_STAT_VALUE = 0xFFFFFFFF;

    private RunListWidget runList;
    private LootrunStore.LifetimeStats lifetimeStats;

    public LootrunHistoryScreen() {
        super(Component.literal("Lootrun History"));
    }

    @Override
    protected void init() {
        lifetimeStats = LootrunStore.getLifetimeStats();

        int listTop = 52;
        int listHeight = this.height - listTop - 4;

        runList = new RunListWidget(minecraft, this.width, listHeight, listTop, 20);
        addRenderableWidget(runList);

        refreshList();
    }

    private void refreshList() {
        if (runList == null) return;

        List<LootrunRecord> records = LootrunStore.getRecords(); // already sorted newest-first
        runList.clear();
        for (LootrunRecord record : records) {
            runList.add(new RunListWidget.RunEntry(record));
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Title
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 4, 0xFFFFFF);

        // Lifetime stats summary bar
        if (lifetimeStats != null) {
            renderStatsBar(guiGraphics);
        }

        // Column headers
        int headerY = 42;
        int left = 20;
        guiGraphics.drawString(this.font, "Date", left, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Location", left + 72, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Chall", left + 172, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Pulls", left + 208, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Rerolls", left + 248, headerY, COLOR_HEADER);

        // Right-aligned columns
        String chestsStr = "Chests";
        String itemsStr = "Items";
        String timeStr = "Time";
        guiGraphics.drawString(this.font, chestsStr, this.width - 30 - this.font.width("00:00") - 10 - this.font.width("000") - 10 - this.font.width("000"), headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, itemsStr, this.width - 30 - this.font.width("00:00") - 10 - this.font.width("000"), headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, timeStr, this.width - 30 - this.font.width("00:00"), headerY, COLOR_HEADER);
    }

    private void renderStatsBar(GuiGraphics guiGraphics) {
        int y = 16;

        // Background bar
        guiGraphics.fill(4, y - 2, this.width - 4, y + 12, 0x80000000);

        int x = 8;

        // Total runs
        x = drawStat(guiGraphics, "Runs: ", String.valueOf(lifetimeStats.totalRuns), x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
        x += 10;

        // Avg pulls/run
        String avgPullsStr = String.format("%.1f", lifetimeStats.avgPullsPerRun);
        x = drawStat(guiGraphics, "Avg Pulls: ", avgPullsStr, x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
        x += 10;

        // Avg challenges/run
        String avgChallStr = String.format("%.1f", lifetimeStats.avgChallengesPerRun);
        x = drawStat(guiGraphics, "Avg Chall: ", avgChallStr, x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
        x += 10;

        // Total pulls
        x = drawStat(guiGraphics, "Total Pulls: ", String.valueOf(lifetimeStats.totalPulls), x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
        x += 10;

        // Total reward chests
        x = drawStat(guiGraphics, "Chests: ", String.valueOf(lifetimeStats.totalRewardChests), x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
        x += 10;

        // Total items looted
        drawStat(guiGraphics, "Items: ", String.valueOf(lifetimeStats.totalItemsLooted), x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
    }

    private int drawStat(GuiGraphics guiGraphics, String label, String value, int x, int y, int labelColor, int valueColor) {
        guiGraphics.drawString(this.font, label, x, y, labelColor);
        int labelWidth = this.font.width(label);
        guiGraphics.drawString(this.font, value, x + labelWidth, y, valueColor);
        return x + labelWidth + this.font.width(value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ────────────────────────────────────────────────────────────────────
    //  List widget
    // ────────────────────────────────────────────────────────────────────

    private static class RunListWidget extends AbstractSelectionList<RunListWidget.RunEntry> {

        public RunListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return this.width - 40;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }

        /** Expose protected clearEntries to the enclosing screen. */
        public void clear() {
            clearEntries();
        }

        /** Expose protected addEntry to the enclosing screen. */
        public void add(RunEntry entry) {
            addEntry(entry);
        }

        // ────────────────────────────────────────────────────────────────
        //  List entry (one row per lootrun) — nested inside RunListWidget
        //  so it can access the protected Entry inner class
        // ────────────────────────────────────────────────────────────────

        static class RunEntry extends AbstractSelectionList.Entry<RunEntry> {

            private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm")
                    .withZone(ZoneId.systemDefault());

            private static final int COLOR_DATE = 0xFFAAAAAA;
            private static final int COLOR_VALUE = 0xFFFFFFFF;
            private static final int COLOR_LOCATION = 0xFF88BBFF;

            private final LootrunRecord record;
            private final String dateStr;
            private final String locationStr;
            private final String challengesStr;
            private final String pullsStr;
            private final String rerollsStr;
            private final String chestsStr;
            private final String itemsStr;
            private final String timeStr;

            public RunEntry(LootrunRecord record) {
                this.record = record;
                this.dateStr = DATE_FORMAT.format(Instant.ofEpochMilli(record.endTime));
                this.locationStr = record.location != null ? record.location : "";
                this.challengesStr = String.valueOf(record.challengesCompleted);
                this.pullsStr = String.valueOf(record.pullsEarned);
                this.rerollsStr = String.valueOf(record.rerollsEarned);
                this.chestsStr = String.valueOf(record.rewardChestsOpened);
                this.itemsStr = record.itemsLooted > 0 ? String.valueOf(record.itemsLooted) : "-";
                this.timeStr = formatDuration(record.getDurationSeconds());
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width,
                               int height, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
                Minecraft mc = Minecraft.getInstance();
                int textY = top + (height - 9) / 2;

                // Date
                guiGraphics.drawString(mc.font, dateStr, left + 2, textY, COLOR_DATE);

                // Location
                guiGraphics.drawString(mc.font, locationStr, left + 72, textY, COLOR_LOCATION);

                // Challenges
                guiGraphics.drawString(mc.font, challengesStr, left + 172, textY, COLOR_VALUE);

                // Pulls
                guiGraphics.drawString(mc.font, pullsStr, left + 208, textY, COLOR_VALUE);

                // Rerolls
                guiGraphics.drawString(mc.font, rerollsStr, left + 248, textY, COLOR_VALUE);

                // Right-aligned: Chests, Items, Time
                int timeWidth = mc.font.width(timeStr);
                int timeX = left + width - timeWidth - 4;
                guiGraphics.drawString(mc.font, timeStr, timeX, textY, COLOR_VALUE);

                int itemsWidth = mc.font.width(itemsStr);
                int itemsX = timeX - itemsWidth - 10;
                guiGraphics.drawString(mc.font, itemsStr, itemsX, textY, COLOR_VALUE);

                int chestsWidth = mc.font.width(chestsStr);
                int chestsX = itemsX - chestsWidth - 10;
                guiGraphics.drawString(mc.font, chestsStr, chestsX, textY, COLOR_VALUE);

                // Hover highlight
                if (isMouseOver) {
                    guiGraphics.fill(left, top, left + width, top + height, 0x20FFFFFF);
                }
            }

            private static String formatDuration(int totalSeconds) {
                if (totalSeconds <= 0) return "?";
                int minutes = totalSeconds / 60;
                int seconds = totalSeconds % 60;
                return String.format("%d:%02d", minutes, seconds);
            }
        }
    }
}
