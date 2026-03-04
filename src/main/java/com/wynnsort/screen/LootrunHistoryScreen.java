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
    private static final int COLOR_COMPLETED = 0xFF55FF55;
    private static final int COLOR_FAILED = 0xFFFF5555;

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
        guiGraphics.drawString(this.font, "Chall", left + 72, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Pulls", left + 112, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Rerolls", left + 152, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Sacr", left + 204, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "XP", left + 244, headerY, COLOR_HEADER);
        guiGraphics.drawString(this.font, "Time", left + 294, headerY, COLOR_HEADER);

        String outcomeStr = "Result";
        int outcomeWidth = this.font.width(outcomeStr);
        guiGraphics.drawString(this.font, outcomeStr, this.width - 30 - outcomeWidth, headerY, COLOR_HEADER);
    }

    private void renderStatsBar(GuiGraphics guiGraphics) {
        int y = 16;

        // Background bar
        guiGraphics.fill(4, y - 2, this.width - 4, y + 12, 0x80000000);

        int x = 8;

        // Total runs
        x = drawStat(guiGraphics, "Runs: ", String.valueOf(lifetimeStats.totalRuns), x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
        x += 10;

        // Completion rate
        String rateStr = String.format("%.0f%%", lifetimeStats.completionRate);
        int rateColor = lifetimeStats.completionRate >= 80 ? COLOR_COMPLETED :
                        lifetimeStats.completionRate >= 50 ? 0xFFFFAA00 : COLOR_FAILED;
        x = drawStat(guiGraphics, "Rate: ", rateStr, x, y, COLOR_STAT_LABEL, rateColor);
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

        // Total XP
        String xpStr = formatXp(lifetimeStats.totalXp);
        drawStat(guiGraphics, "Total XP: ", xpStr, x, y, COLOR_STAT_LABEL, COLOR_STAT_VALUE);
    }

    private int drawStat(GuiGraphics guiGraphics, String label, String value, int x, int y, int labelColor, int valueColor) {
        guiGraphics.drawString(this.font, label, x, y, labelColor);
        int labelWidth = this.font.width(label);
        guiGraphics.drawString(this.font, value, x + labelWidth, y, valueColor);
        return x + labelWidth + this.font.width(value);
    }

    private static String formatXp(long xp) {
        if (xp >= 1_000_000) {
            return String.format("%.1fM", xp / 1_000_000.0);
        } else if (xp >= 1_000) {
            return String.format("%.1fK", xp / 1_000.0);
        }
        return String.valueOf(xp);
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
            private static final int COLOR_COMPLETED = 0xFF55FF55;
            private static final int COLOR_FAILED = 0xFFFF5555;

            private final LootrunRecord record;
            private final String dateStr;
            private final String challengesStr;
            private final String pullsStr;
            private final String rerollsStr;
            private final String sacrificesStr;
            private final String xpStr;
            private final String timeStr;
            private final String outcomeStr;
            private final int outcomeColor;

            public RunEntry(LootrunRecord record) {
                this.record = record;
                this.dateStr = DATE_FORMAT.format(Instant.ofEpochMilli(record.endTime));
                this.challengesStr = String.valueOf(record.challengesCompleted);
                this.pullsStr = String.valueOf(record.pullsEarned);
                this.rerollsStr = String.valueOf(record.rerollsEarned);
                this.sacrificesStr = String.valueOf(record.sacrifices);
                this.xpStr = formatXp(record.xpEarned);
                this.timeStr = formatDuration(record.getDurationSeconds());
                this.outcomeStr = record.completed ? "OK" : "FAIL";
                this.outcomeColor = record.completed ? COLOR_COMPLETED : COLOR_FAILED;
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width,
                               int height, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
                Minecraft mc = Minecraft.getInstance();
                int textY = top + (height - 9) / 2;

                // Date
                guiGraphics.drawString(mc.font, dateStr, left + 2, textY, COLOR_DATE);

                // Challenges
                guiGraphics.drawString(mc.font, challengesStr, left + 72, textY, COLOR_VALUE);

                // Pulls
                guiGraphics.drawString(mc.font, pullsStr, left + 112, textY, COLOR_VALUE);

                // Rerolls
                guiGraphics.drawString(mc.font, rerollsStr, left + 152, textY, COLOR_VALUE);

                // Sacrifices
                guiGraphics.drawString(mc.font, sacrificesStr, left + 204, textY, COLOR_VALUE);

                // XP
                guiGraphics.drawString(mc.font, xpStr, left + 244, textY, COLOR_VALUE);

                // Duration
                guiGraphics.drawString(mc.font, timeStr, left + 294, textY, COLOR_VALUE);

                // Outcome (right-aligned)
                int outcomeWidth = mc.font.width(outcomeStr);
                guiGraphics.drawString(mc.font, outcomeStr, left + width - outcomeWidth - 4, textY, outcomeColor);

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
