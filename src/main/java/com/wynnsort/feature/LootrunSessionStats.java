package com.wynnsort.feature;

import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.models.containers.Container;
import com.wynntils.models.lootrun.beacons.LootrunBeaconKind;
import com.wynntils.models.lootrun.event.LootrunFinishedEvent;
import com.wynntils.models.lootrun.type.LootrunningState;
import com.wynntils.utils.type.CappedValue;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks per-session lootrun statistics and renders a compact HUD panel
 * showing challenge count, pulls, rerolls, sacrifices, beacon summary, and duration.
 *
 * Uses a hybrid approach:
 * - Subscribes to Wynntils events for end-of-run stats (LootrunFinishedEvent)
 * - Polls Models.Lootrun each HUD tick for live tracking of challenges, beacons, etc.
 *
 * The HUD panel is positioned on the right side of the screen, below the beacon tracker.
 */
public class LootrunSessionStats implements HudRenderCallback {

    public static final LootrunSessionStats INSTANCE = new LootrunSessionStats();
    private static final FeatureLogger LOG = new FeatureLogger("LRStats", DiagnosticLog.Category.LOOTRUN);

    // ── Colors ────────────────────────────────────────────────────────────
    private static final int COLOR_HEADER = 0xFFFF8800;    // orange header
    private static final int COLOR_LABEL = 0xFFCCCCCC;     // light gray for labels
    private static final int COLOR_VALUE = 0xFF55FF55;     // green for values
    private static final int COLOR_BEACON = 0xFFFFAA00;    // orange for beacon summary
    private static final int COLOR_DURATION = 0xFF88BBFF;  // light blue for duration
    private static final int COLOR_BG = 0x80000000;        // semi-transparent black

    // ── State ─────────────────────────────────────────────────────────────

    /** Current active session data, null if no lootrun is active. */
    private LootrunSessionData currentSession = null;

    /** Last completed/failed session data for brief post-run display. */
    private LootrunSessionData lastSession = null;

    /** Timestamp when last session ended (for timed post-run display). */
    private long lastSessionEndTime = 0;

    /** How long to show the end-of-run summary (10 seconds). */
    private static final long POST_RUN_DISPLAY_MS = 10_000;

    // ── Poll state for detecting changes ──────────────────────────────────

    private LootrunningState lastState = LootrunningState.NOT_RUNNING;
    private int lastChallengesCurrent = 0;
    private int lastRerolls = 0;
    private int lastSacrifices = 0;

    /**
     * Multiplier for the next beacon's effects from aqua beacon.
     * Normal aqua: 2x, Vibrant aqua: 3x. Stacks multiplicatively.
     * Resets to 1 after the next non-aqua beacon is selected.
     */
    private int pendingAquaMultiplier = 1;

    /** Whether the lootrun location helper is available. */
    private static boolean locationHelperAvailable = false;
    private static boolean locationHelperChecked = false;

    /** Throttle location lookups to once per second (reflection-based, avoid per-frame). */
    private long lastLocationAttemptMs = 0;
    private static final long LOCATION_ATTEMPT_INTERVAL_MS = 1000;

    /** Last reward chest container ID we counted, to avoid double-counting from repeated events. */
    private int lastRewardChestContainerId = -1;

    private LootrunSessionStats() {}

    /**
     * Checks if the current container is a lootrun reward chest.
     * Shared by DryStreakTracker and LootrunSessionStats to avoid duplication.
     */
    static boolean isCurrentContainerRewardChest() {
        try {
            Container container = Models.Container.getCurrentContainer();
            if (container == null) return false;
            String className = container.getClass().getSimpleName();
            String containerName = container.getContainerName();
            return className.contains("LootrunRewardChest")
                    || (containerName != null && containerName.toLowerCase().contains("lootrun reward"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the currently active session data, or null if no lootrun is active.
     */
    public LootrunSessionData getCurrentSession() {
        return currentSession;
    }

    /**
     * Returns the most recently completed/failed session data, or null if none.
     * Used by LootrunHistoryFeature to capture session-tracked data (location, chests, items)
     * after this handler has already moved currentSession to lastSession.
     */
    public LootrunSessionData getLastSession() {
        return lastSession;
    }

    // ── Wynntils Event Subscriptions ─────────────────────────────────────

    /**
     * Captures end-of-run statistics when a lootrun is completed successfully.
     */
    @SubscribeEvent
    public void onLootrunCompleted(LootrunFinishedEvent.Completed event) {
        try {
            LOG.info("LootrunFinishedEvent.Completed: challenges={}, time={}, pulls={}, rerolls={}, sacrifices={}, mobs={}, chests={}, xp={}",
                    event.getChallengesCompleted(), event.getTimeElapsed(),
                    event.getRewardPulls(), event.getRewardRerolls(), event.getRewardSacrifices(),
                    event.getMobsKilled(), event.getChestsOpened(), event.getExperienceGained());

            if (currentSession != null) {
                currentSession.completed = true;
                currentSession.endTime = System.currentTimeMillis();
                currentSession.challengesCompleted = event.getChallengesCompleted();
                currentSession.pullsEarned = event.getRewardPulls();
                currentSession.rerollsEarned = event.getRewardRerolls();
                currentSession.sacrifices = event.getRewardSacrifices();
                currentSession.mobsKilled = event.getMobsKilled();
                currentSession.chestsOpened = event.getChestsOpened();

                lastSession = currentSession;
                lastSessionEndTime = System.currentTimeMillis();
                currentSession = null;

                LOG.info("Lootrun session completed! Beacons: {}", lastSession.getBeaconSummary());
                Map<String, Object> evtData = new LinkedHashMap<>();
                evtData.put("challenges", lastSession.challengesCompleted);
                evtData.put("pulls", lastSession.pullsEarned);
                evtData.put("rerolls", lastSession.rerollsEarned);
                evtData.put("sacrifices", lastSession.sacrifices);
                evtData.put("beacons", lastSession.getBeaconSummary());
                LOG.event("session_completed", evtData);
            }
        } catch (Exception e) {
            LOG.error("Error handling LootrunFinishedEvent.Completed", e);
        }
    }

    /**
     * Captures failed run statistics.
     */
    @SubscribeEvent
    public void onLootrunFailed(LootrunFinishedEvent.Failed event) {
        try {
            LOG.info("LootrunFinishedEvent.Failed: challenges={}, time={}",
                    event.getChallengesCompleted(), event.getTimeElapsed());

            if (currentSession != null) {
                currentSession.completed = false;
                currentSession.endTime = System.currentTimeMillis();
                currentSession.challengesCompleted = event.getChallengesCompleted();

                lastSession = currentSession;
                lastSessionEndTime = System.currentTimeMillis();
                currentSession = null;

                LOG.info("Lootrun session failed. Beacons: {}", lastSession.getBeaconSummary());
                Map<String, Object> evtData = new LinkedHashMap<>();
                evtData.put("challenges", lastSession.challengesCompleted);
                evtData.put("beacons", lastSession.getBeaconSummary());
                LOG.event("session_failed", evtData);
            }
        } catch (Exception e) {
            LOG.error("Error handling LootrunFinishedEvent.Failed", e);
        }
    }

    // ── HUD Rendering + Poll Logic ───────────────────────────────────────

    @Override
    public void onHudRender(GuiGraphics guiGraphics, DeltaTracker tickCounter) {
        if (!WynnSortConfig.INSTANCE.lootrunStatsHudEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        LootrunningState currentState;
        try {
            currentState = Models.Lootrun.getState();
        } catch (Exception e) {
            LOG.warn("Models.Lootrun.getState() failed: {}", e.getMessage());
            return;
        }

        // Log only on state changes (not every tick)

        // Detect lootrun start
        if (currentState.isRunning() && !lastState.isRunning()) {
            LOG.info("Lootrun started! Creating new session. State transition: {} -> {} (raw enum: {})",
                    lastState, currentState, currentState.name());
            currentSession = new LootrunSessionData();
            lastChallengesCurrent = 0;
            lastRerolls = 0;
            lastSacrifices = 0;
            pendingAquaMultiplier = 1;
            lastRewardChestContainerId = -1;
            LOG.event("session_started", Map.of("state", currentState.name()));
        }

        // Try to capture location if not yet determined (may be populated after first challenge)
        if (currentState.isRunning() && currentSession != null && currentSession.location == null) {
            tryCaptureLootrunLocation();
        }

        // Detect lootrun end (backup in case events don't fire)
        if (!currentState.isRunning() && lastState.isRunning()) {
            if (currentSession != null) {
                LOG.info("Lootrun ended via state poll (events may have already handled this).");
                if (currentSession.endTime == 0) {
                    currentSession.endTime = System.currentTimeMillis();
                    lastSession = currentSession;
                    lastSessionEndTime = System.currentTimeMillis();
                    currentSession = null;
                }
            }
        }

        // Poll for live stats while lootrun is active
        if (currentState.isRunning() && currentSession != null) {
            pollLiveStats(currentState);
        }

        lastState = currentState;

        // Decide what to render
        if (currentSession != null) {
            renderSessionHud(guiGraphics, mc, currentSession, true);
        } else if (lastSession != null && (System.currentTimeMillis() - lastSessionEndTime) < POST_RUN_DISPLAY_MS) {
            renderSessionHud(guiGraphics, mc, lastSession, false);
        }
    }

    // ── Live Polling ─────────────────────────────────────────────────────

    /**
     * Polls Wynntils Models.Lootrun for live stats during an active run.
     * Detects changes in challenges, rerolls, sacrifices, and beacon selections.
     */
    private void pollLiveStats(LootrunningState currentState) {
        if (currentSession == null) return;

        try {
            // Track challenges via CappedValue
            CappedValue challenges = Models.Lootrun.getChallenges();
            if (challenges != null) {
                int currentChallenges = challenges.current();
                if (currentChallenges > lastChallengesCurrent) {
                    int delta = currentChallenges - lastChallengesCurrent;
                    currentSession.challengesCompleted = currentChallenges;

                    currentSession.pullsEarned += delta;  // 1 pull per challenge
                    LOG.info("Challenges updated: {} -> {} (+{} pulls, pulls now {})",
                            lastChallengesCurrent, currentChallenges, delta,
                            currentSession.pullsEarned);
                }
                lastChallengesCurrent = currentChallenges;
            }
        } catch (Exception e) {
            LOG.warn("API poll failed (challenges): {}", e.getMessage());
        }

        try {
            // Track rerolls
            int rerolls = Models.Lootrun.getRerolls();
            if (rerolls > lastRerolls) {
                currentSession.rerollsEarned = rerolls;
                LOG.info("Rerolls updated: {} -> {}", lastRerolls, rerolls);
            }
            lastRerolls = rerolls;
        } catch (Exception e) {
            LOG.warn("API poll failed (rerolls): {}", e.getMessage());
        }

        try {
            // Track sacrifices
            int sacrifices = Models.Lootrun.getSacrifices();
            if (sacrifices > lastSacrifices) {
                currentSession.sacrifices = sacrifices;
                LOG.info("Sacrifices updated: {} -> {}", lastSacrifices, sacrifices);
            }
            lastSacrifices = sacrifices;
        } catch (Exception e) {
            LOG.warn("API poll failed (sacrifices): {}", e.getMessage());
        }

        // Detect beacon selections via state transitions (CHOOSING_BEACON -> IN_TASK)
        if (currentState == LootrunningState.IN_TASK && lastState == LootrunningState.CHOOSING_BEACON) {
            try {
                LootrunBeaconKind lastBeacon = Models.Lootrun.getLastTaskBeaconColor();
                boolean vibrant = false;
                try {
                    vibrant = Models.Lootrun.wasLastBeaconVibrant();
                } catch (Exception e) {
                    LOG.warn("wasLastBeaconVibrant() not available: {}", e.getMessage());
                }

                String colorName = lastBeacon != null ? lastBeacon.name() : "UNKNOWN";
                currentSession.recordBeacon(colorName, vibrant);

                // Aqua boosts the NEXT beacon's effects (2x normal, 3x vibrant).
                // Must be checked first so the multiplier is set before a pull-giving beacon.
                if (lastBeacon == LootrunBeaconKind.AQUA) {
                    int mul = vibrant ? 3 : 2;
                    pendingAquaMultiplier *= mul;
                    LOG.info("Aqua beacon{}: next beacon {}x (pending multiplier now {}x)",
                            vibrant ? " (vibrant)" : "", mul, pendingAquaMultiplier);
                } else {
                    // Apply aqua multiplier to pull-giving beacons, then reset
                    int aquaMul = pendingAquaMultiplier;

                    if (lastBeacon == LootrunBeaconKind.PURPLE) {
                        int basePulls = vibrant ? 2 : 1;
                        int pulls = basePulls * aquaMul;
                        currentSession.pullsEarned += pulls;
                        LOG.info("Purple beacon{}{}: +{} pull(s) (pulls now {})",
                                vibrant ? " (vibrant)" : "",
                                aquaMul > 1 ? " (aqua " + aquaMul + "x)" : "",
                                pulls, currentSession.pullsEarned);
                    } else if (lastBeacon == LootrunBeaconKind.DARK_GRAY) {
                        int basePulls = vibrant ? 6 : 3;
                        int pulls = basePulls * aquaMul;
                        currentSession.pullsEarned += pulls;
                        LOG.info("Dark gray beacon{}{}: +{} pulls (pulls now {})",
                                vibrant ? " (vibrant)" : "",
                                aquaMul > 1 ? " (aqua " + aquaMul + "x)" : "",
                                pulls, currentSession.pullsEarned);
                    }

                    // Reset aqua multiplier after any non-aqua beacon
                    if (aquaMul > 1) {
                        LOG.info("Aqua multiplier consumed (was {}x)", aquaMul);
                    }
                    pendingAquaMultiplier = 1;
                }

                LOG.info("Beacon selected: {} (vibrant={}), total beacons: {}",
                        colorName, vibrant, currentSession.beaconsSelected);
            } catch (Exception e) {
                // If we can't determine the beacon color, record as unknown
                currentSession.recordBeacon("UNKNOWN", false);
                LOG.warn("Failed to get beacon color on selection", e);
            }
        }
    }

    // ── Location Capture ───────────────────────────────────────────────

    /**
     * Attempts to determine the current lootrun location via the isolated helper.
     */
    private void tryCaptureLootrunLocation() {
        long now = System.currentTimeMillis();
        if (now - lastLocationAttemptMs < LOCATION_ATTEMPT_INTERVAL_MS) return;
        lastLocationAttemptMs = now;

        if (!locationHelperChecked) {
            locationHelperChecked = true;
            try {
                com.wynnsort.util.LootrunLocationHelper.getCurrentLocationName();
                locationHelperAvailable = true;
                LOG.info("LootrunLocationHelper available");
            } catch (Throwable t) {
                locationHelperAvailable = false;
                LOG.info("LootrunLocationHelper not available: {}", t.getMessage());
            }
        }
        if (!locationHelperAvailable) return;

        try {
            String loc = com.wynnsort.util.LootrunLocationHelper.getCurrentLocationName();
            if (loc != null && currentSession != null) {
                currentSession.location = loc;
                LOG.info("Captured lootrun location: {}", loc);
            }
        } catch (Throwable t) {
            LOG.warn("Failed to capture lootrun location: {}", t.getMessage());
        }
    }

    // ── Reward Chest Tracking ───────────────────────────────────────────

    /**
     * Detects reward chest containers during active lootruns and counts
     * chests opened and items obtained.
     */
    @SubscribeEvent
    public void onContainerContent(ContainerSetContentEvent.Post event) {
        if (currentSession == null) return;

        // Only track during active lootruns
        LootrunningState state;
        try {
            state = Models.Lootrun.getState();
        } catch (Exception e) {
            return;
        }
        if (!state.isRunning()) return;

        if (!isCurrentContainerRewardChest()) return;

        // Get container ID for deduplication
        int containerId = -1;
        try {
            Container container = Models.Container.getCurrentContainer();
            if (container != null) containerId = container.getContainerId();
        } catch (Exception e) { /* ignore */ }

        // Deduplicate: ContainerSetContentEvent can fire multiple times for the same chest
        if (containerId >= 0 && containerId == lastRewardChestContainerId) return;
        lastRewardChestContainerId = containerId;

        // Count the reward chest
        currentSession.rewardChestsOpened++;

        // Count items in the chest (respecting stack sizes for emeralds, materials, etc.)
        List<ItemStack> items = event.getItems();
        int itemCount = 0;
        if (items != null) {
            for (ItemStack stack : items) {
                if (stack != null && !stack.isEmpty()) {
                    itemCount += stack.getCount();
                }
            }
        }
        currentSession.itemsLooted += itemCount;

        LOG.info("Reward chest opened (#{}) with {} items (total items: {})",
                currentSession.rewardChestsOpened, itemCount, currentSession.itemsLooted);
    }

    // ── HUD Rendering ────────────────────────────────────────────────────

    /**
     * Renders the session stats HUD panel on the right side of the screen,
     * positioned below the beacon tracker.
     *
     * @param session the session data to render
     * @param active  true if the session is still active, false for post-run display
     */
    private void renderSessionHud(GuiGraphics guiGraphics, Minecraft mc, LootrunSessionData session, boolean active) {
        int lineHeight = 11;
        int padding = 4;
        int boxWidth = 160;
        WynnSortConfig cfg = WynnSortConfig.INSTANCE;

        // Dry streak data (show if enabled)
        DryStreakTracker.DryStreakData dryData = (cfg.dryStreakEnabled && cfg.showStatsMythicStats)
                ? DryStreakTracker.INSTANCE.getData() : null;

        // Count visible lines
        int lineCount = 1; // header always shown
        if (cfg.showStatsChallenges) lineCount++;
        if (cfg.showStatsPullsRerolls) lineCount++;
        if (session.itemsLooted > 0) lineCount++;
        if (cfg.showStatsMythicChance && session.getEffectivePulls() > 0) lineCount++;
        if (cfg.showStatsSacrifices && session.sacrifices > 0) lineCount++;
        if (cfg.showStatsBeaconSummary && !session.beaconCounts.isEmpty()) lineCount++;
        if (cfg.showStatsDuration) lineCount++;
        if (dryData != null) {
            lineCount++; // separator/header
            if (dryData.totalLifetimePulls > 0) lineCount++;
            if (dryData.totalPullsWithoutMythic > 0) lineCount++;
            if (dryData.longestDryStreak > 0) lineCount++;
            if (dryData.lastMythicName != null && !dryData.lastMythicName.isEmpty()) lineCount++;
        }

        int boxHeight = lineCount * lineHeight + padding * 2;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Use configured position or default (top-right)
        float cfgX = cfg.sessionStatsHudX;
        float cfgY = cfg.sessionStatsHudY;
        int x = cfgX >= 0 ? (int) (cfgX * screenWidth) : screenWidth - boxWidth - 4;
        int y = cfgY >= 0 ? (int) (cfgY * screenHeight) : 4;
        x = Math.max(0, Math.min(x, screenWidth - boxWidth));
        y = Math.max(0, Math.min(y, screenHeight - boxHeight));

        // Background
        guiGraphics.fill(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2, COLOR_BG);

        int textY = y + padding;

        // Header
        String headerText = active ? "Lootrun Stats" : (session.completed ? "Run Complete!" : "Run Failed");
        int headerColor = active ? COLOR_HEADER : (session.completed ? COLOR_VALUE : 0xFFFF4444);
        guiGraphics.drawString(mc.font, headerText, x + padding, textY, headerColor);
        textY += lineHeight;

        // Challenges
        if (cfg.showStatsChallenges) {
            String challengeText = "Run: " + session.challengesCompleted + " challenges";
            guiGraphics.drawString(mc.font, challengeText, x + padding, textY, COLOR_LABEL);
            textY += lineHeight;
        }

        // Pulls & Rerolls (with effective pulls)
        if (cfg.showStatsPullsRerolls) {
            int effectivePulls = session.getEffectivePulls();
            String pullsText = "Pulls: " + session.pullsEarned + " | Rerolls: " + session.rerollsEarned;
            if (session.rerollsEarned > 0) {
                pullsText += " (" + effectivePulls + " eff.)";
            }
            guiGraphics.drawString(mc.font, pullsText, x + padding, textY, COLOR_LABEL);
            textY += lineHeight;
        }

        // Items looted from reward chests
        if (session.itemsLooted > 0) {
            String itemsText = "Items looted: " + session.itemsLooted;
            guiGraphics.drawString(mc.font, itemsText, x + padding, textY, COLOR_LABEL);
            textY += lineHeight;
        }

        // Mythic chance based on effective pulls
        if (cfg.showStatsMythicChance) {
            int effectivePulls = session.getEffectivePulls();
            if (effectivePulls > 0) {
                double mythicChance = (1.0 - Math.pow(1.0 - DryStreakTracker.MYTHIC_RATE, effectivePulls)) * 100.0;
                String mythicText = String.format("Mythic chance: %.1f%%", mythicChance);
                guiGraphics.drawString(mc.font, mythicText, x + padding, textY, 0xFFFF55FF);
                textY += lineHeight;
            }
        }

        // Sacrifices (only show if > 0)
        if (cfg.showStatsSacrifices && session.sacrifices > 0) {
            String sacText = "Sacrifices: " + session.sacrifices;
            guiGraphics.drawString(mc.font, sacText, x + padding, textY, COLOR_LABEL);
            textY += lineHeight;
        }

        // Beacon summary (only show if beacons were selected)
        if (cfg.showStatsBeaconSummary && !session.beaconCounts.isEmpty()) {
            String beaconText = "Beacons: " + session.getBeaconSummary();
            guiGraphics.drawString(mc.font, beaconText, x + padding, textY, COLOR_BEACON);
            textY += lineHeight;
        }

        // Duration
        if (cfg.showStatsDuration) {
            String durationText = "Time: " + session.getFormattedDuration();
            guiGraphics.drawString(mc.font, durationText, x + padding, textY, COLOR_DURATION);
            textY += lineHeight;
        }

        // Dry streak details (lifetime stats, percentile, etc.)
        if (dryData != null) {
            guiGraphics.drawString(mc.font, "--- Mythic Stats ---", x + padding, textY, COLOR_HEADER);
            textY += lineHeight;

            if (dryData.totalLifetimePulls > 0) {
                double expected = dryData.totalLifetimePulls * DryStreakTracker.MYTHIC_RATE;
                String lifetimeText = String.format("%d pulls, %d mythics (%.1f exp)",
                        dryData.totalLifetimePulls, dryData.mythicsFound, expected);
                guiGraphics.drawString(mc.font, lifetimeText, x + padding, textY, COLOR_LABEL);
                textY += lineHeight;
            }

            if (dryData.totalPullsWithoutMythic > 0) {
                double pct = (1.0 - Math.pow(1.0 - DryStreakTracker.MYTHIC_RATE, dryData.totalPullsWithoutMythic)) * 100.0;
                String pctText = String.format("%.0f%% would have found one", pct);
                guiGraphics.drawString(mc.font, pctText, x + padding, textY, COLOR_LABEL);
                textY += lineHeight;
            }

            if (dryData.longestDryStreak > 0) {
                String longestText = "Longest dry: " + dryData.longestDryStreak + " pulls";
                guiGraphics.drawString(mc.font, longestText, x + padding, textY, COLOR_LABEL);
                textY += lineHeight;
            }

            if (dryData.lastMythicName != null && !dryData.lastMythicName.isEmpty()) {
                String timeAgo = DryStreakTracker.formatTimeAgo(dryData.lastMythicTimestamp);
                String lastText = "Last: " + dryData.lastMythicName + " (" + timeAgo + ")";
                guiGraphics.drawString(mc.font, lastText, x + padding, textY, COLOR_LABEL);
            }
        }
    }
}
