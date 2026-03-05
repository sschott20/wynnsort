package com.wynnsort.feature;

import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynntils.core.components.Models;
import com.wynntils.models.lootrun.beacons.LootrunBeaconKind;
import com.wynntils.models.lootrun.event.LootrunFinishedEvent;
import com.wynntils.models.lootrun.type.LootrunningState;
import com.wynntils.utils.type.CappedValue;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.LinkedHashMap;
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
     * Number of upcoming challenge completions whose pull reward is doubled by aqua beacon.
     * Aqua beacon doubles ALL rewards from the next challenge, so completing a challenge
     * while this counter is > 0 gives 2 pulls instead of 1.
     * Normal aqua: 1 challenge doubled. Vibrant aqua: 2 challenges doubled.
     */
    private int pendingAquaChallenges = 0;


    private LootrunSessionStats() {}

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
                currentSession.xpEarned = event.getExperienceGained();

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
            pendingAquaChallenges = 0;
            LOG.event("session_started", Map.of("state", currentState.name()));
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

                    // Each challenge gives 1 pull, but aqua beacon doubles the reward.
                    // pendingAquaChallenges tracks how many upcoming challenges are boosted.
                    int pullsFromChallenges = 0;
                    for (int i = 0; i < delta; i++) {
                        if (pendingAquaChallenges > 0) {
                            pullsFromChallenges += 2;  // aqua doubles: 1 pull * 2 = 2 pulls
                            pendingAquaChallenges--;
                            LOG.info("Aqua-boosted challenge completion: 2 pulls (remaining aqua charges: {})",
                                    pendingAquaChallenges);
                        } else {
                            pullsFromChallenges += 1;  // normal: 1 pull per challenge
                        }
                    }
                    currentSession.pullsEarned += pullsFromChallenges;
                    LOG.info("Challenges updated: {} -> {} (+{}, +{} pulls, pulls now {})",
                            lastChallengesCurrent, currentChallenges, delta,
                            pullsFromChallenges, currentSession.pullsEarned);
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

                // Purple beacons give +1 pull (+2 if vibrant), dark gray gives +3 (+6 if vibrant)
                if (lastBeacon == LootrunBeaconKind.PURPLE) {
                    int pulls = vibrant ? 2 : 1;
                    currentSession.pullsEarned += pulls;
                    LOG.info("Purple beacon{}: +{} pull(s) (pulls now {})",
                            vibrant ? " (vibrant)" : "", pulls, currentSession.pullsEarned);
                } else if (lastBeacon == LootrunBeaconKind.DARK_GRAY) {
                    int pulls = vibrant ? 6 : 3;
                    currentSession.pullsEarned += pulls;
                    LOG.info("Dark gray beacon{}: +{} pulls (pulls now {})",
                            vibrant ? " (vibrant)" : "", pulls, currentSession.pullsEarned);
                } else if (lastBeacon == LootrunBeaconKind.AQUA) {
                    // Aqua beacon doubles ALL rewards from the next challenge completion.
                    // Normal aqua: next 1 challenge doubled. Vibrant aqua: next 2 challenges doubled.
                    int aquaCharges = vibrant ? 2 : 1;
                    pendingAquaChallenges += aquaCharges;
                    LOG.info("Aqua beacon{}: +{} aqua charge(s) (pending aqua challenges now {})",
                            vibrant ? " (vibrant)" : "", aquaCharges, pendingAquaChallenges);
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
        int boxWidth = 150;

        // Count visible lines
        int lineCount = 4; // header + challenges + pulls/rerolls + duration
        if (session.sacrifices > 0) lineCount++;
        if (!session.beaconCounts.isEmpty()) lineCount++;

        int boxHeight = lineCount * lineHeight + padding * 2;

        // Position on the right side of the screen, below the beacon tracker
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Beacon tracker is on the left at x=4, center-y. We go right side, offset from top.
        int x = screenWidth - boxWidth - 4;
        int y = 4;

        // Background
        guiGraphics.fill(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2, COLOR_BG);

        int textY = y + padding;

        // Header
        String headerText = active ? "Lootrun Stats" : (session.completed ? "Run Complete!" : "Run Failed");
        int headerColor = active ? COLOR_HEADER : (session.completed ? COLOR_VALUE : 0xFFFF4444);
        guiGraphics.drawString(mc.font, headerText, x + padding, textY, headerColor);
        textY += lineHeight;

        // Challenges
        String challengeText = "Run: " + session.challengesCompleted + " challenges";
        guiGraphics.drawString(mc.font, challengeText, x + padding, textY, COLOR_LABEL);
        textY += lineHeight;

        // Pulls & Rerolls
        String pullsText = "Pulls: " + session.pullsEarned + " | Rerolls: " + session.rerollsEarned;
        guiGraphics.drawString(mc.font, pullsText, x + padding, textY, COLOR_LABEL);
        textY += lineHeight;

        // Sacrifices (only show if > 0)
        if (session.sacrifices > 0) {
            String sacText = "Sacrifices: " + session.sacrifices;
            guiGraphics.drawString(mc.font, sacText, x + padding, textY, COLOR_LABEL);
            textY += lineHeight;
        }

        // Beacon summary (only show if beacons were selected)
        if (!session.beaconCounts.isEmpty()) {
            String beaconText = "Beacons: " + session.getBeaconSummary();
            guiGraphics.drawString(mc.font, beaconText, x + padding, textY, COLOR_BEACON);
            textY += lineHeight;
        }

        // Duration
        String durationText = "Time: " + session.getFormattedDuration();
        guiGraphics.drawString(mc.font, durationText, x + padding, textY, COLOR_DURATION);
    }
}
