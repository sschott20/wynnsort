package com.wynnsort.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynntils.core.components.Models;
import com.wynntils.models.lootrun.beacons.LootrunBeaconKind;
import com.wynntils.models.lootrun.event.LootrunFinishedEvent;
import com.wynntils.models.lootrun.type.LootrunningState;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks all 12 beacon types during lootruns and renders a HUD overlay
 * showing active effects, counts, durations, and vibrant status.
 *
 * Uses a poll-based approach: monitors Wynntils' API getters each tick
 * and detects beacon selections via state transitions.
 *
 * Beacon types tracked:
 *   - Orange/Rainbow: challenge-based duration countdown
 *   - Blue: boon count
 *   - Purple: curse/pull count
 *   - Yellow: flying chest count
 *   - Crimson: trial count (max 2 per run)
 *   - Aqua: boost count (also modifies next beacon duration)
 *   - Green: use count (time bonus)
 *   - Dark Grey: use count (max 1 per run, +3 curses +3 pulls)
 *   - White: use count (max 1 per run, +5 challenges)
 *   - Grey (Light): mission count (max 3 per run)
 *   - Red: use count (cannot appear consecutively)
 *
 * Beacon durations (Base / Vibrant / Vibrant+Aqua):
 *   Orange: 5 / 10 / 30 challenges
 *   Rainbow: 10 / 20 / 60 challenges
 */
public class LootrunBeaconTracker implements HudRenderCallback {

    public static final LootrunBeaconTracker INSTANCE = new LootrunBeaconTracker();
    private static final FeatureLogger LOG = new FeatureLogger("Beacon", DiagnosticLog.Category.BEACON);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STATE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("wynnsort-lootrun-state.json");

    // ── Display colors for each beacon type (ARGB) ────────────────────
    private static final int COLOR_HEADER    = 0xFFFF8800;
    private static final int COLOR_HEALTHY   = 0xFF44FF44;   // green  (>15 remaining)
    private static final int COLOR_WARNING   = 0xFFFFAA00;   // orange (6-15 remaining)
    private static final int COLOR_CRITICAL  = 0xFFFF4444;   // red    (<=5 remaining)

    /** Map from LootrunBeaconKind to ARGB display color for HUD text. */
    private static final Map<LootrunBeaconKind, Integer> BEACON_COLORS = new EnumMap<>(LootrunBeaconKind.class);
    static {
        BEACON_COLORS.put(LootrunBeaconKind.GREEN,     0xFF55FF55);
        BEACON_COLORS.put(LootrunBeaconKind.YELLOW,    0xFFFFFF55);
        BEACON_COLORS.put(LootrunBeaconKind.BLUE,      0xFF5555FF);
        BEACON_COLORS.put(LootrunBeaconKind.PURPLE,    0xFFAA00AA);
        BEACON_COLORS.put(LootrunBeaconKind.GRAY,      0xFFAAAAAA);
        BEACON_COLORS.put(LootrunBeaconKind.ORANGE,    0xFFFFAA00);
        BEACON_COLORS.put(LootrunBeaconKind.RED,       0xFFFF5555);
        BEACON_COLORS.put(LootrunBeaconKind.DARK_GRAY, 0xFF555555);
        BEACON_COLORS.put(LootrunBeaconKind.WHITE,     0xFFFFFFFF);
        BEACON_COLORS.put(LootrunBeaconKind.AQUA,      0xFF55FFFF);
        BEACON_COLORS.put(LootrunBeaconKind.CRIMSON,   0xFFFF0000);
        BEACON_COLORS.put(LootrunBeaconKind.RAINBOW,   0xFFFF44FF);
    }


    // ── Beacon tracking state ──────────────────────────────────────────

    /** Remaining challenge count for each active orange beacon. */
    private final List<Integer> orangeBeacons = new ArrayList<>();

    /** Remaining challenges for rainbow effect, -1 = not active. */
    private int rainbowRemaining = -1;

    /** True if the previous beacon was aqua (boosts the NEXT beacon). */
    private boolean aquaPending = false;

    /** True if the aqua beacon was vibrant (under rainbow -> +200% instead of +100%). */
    private boolean aquaWasVibrant = false;

    /** Per-beacon-type use/effect counts (excludes orange/rainbow which use duration). */
    private final Map<LootrunBeaconKind, Integer> beaconCounts = new EnumMap<>(LootrunBeaconKind.class);

    /** Log of all beacon choices made during this lootrun. */
    private final List<BeaconChoice> beaconChoiceLog = new ArrayList<>();

    // ── Poll-based state detection ─────────────────────────────────────

    /** Last observed lootrun state. */
    private LootrunningState lastState = LootrunningState.NOT_RUNNING;

    /** Whether we've bootstrapped from saved state on first lootrun detection. */
    private boolean bootstrapped = false;

    /** Snapshot of Wynntils orange beacon count when entering CHOOSING_BEACON. */
    private int snapshotOrangeCount = 0;

    /** Snapshot of Wynntils rainbow beacon count when entering CHOOSING_BEACON. */
    private int snapshotRainbowCount = 0;

    /** Timestamp of last periodic state save. */
    private long lastPeriodicSave = 0;

    /** How often to auto-save state during active lootrun (60 seconds). */
    private static final long PERIODIC_SAVE_INTERVAL_MS = 60_000;

    private LootrunBeaconTracker() {}

    // ── BeaconChoice record ────────────────────────────────────────────

    /** Records a single beacon selection event. */
    public static class BeaconChoice {
        public String beaconType;
        public boolean vibrant;
        public int challengeNumber;
        public long timestamp;

        public BeaconChoice() {}

        public BeaconChoice(LootrunBeaconKind kind, boolean vibrant, int challengeNumber) {
            this.beaconType = kind.name();
            this.vibrant = vibrant;
            this.challengeNumber = challengeNumber;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // ── HUD Rendering + Poll Logic ─────────────────────────────────────

    @Override
    public void onHudRender(GuiGraphics guiGraphics, DeltaTracker tickCounter) {
        if (!WynnSortConfig.INSTANCE.lootrunHudEnabled) return;

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
        if (currentState != lastState) {
            LOG.info("State change: {} -> {}, orangeBeacons={}, beaconCounts={}, bootstrapped={}",
                    lastState, currentState, orangeBeacons, beaconCounts, bootstrapped);
        }

        // Lootrun state went to NOT_RUNNING (e.g. /class relog, disconnect)
        // Don't clear state — just save it so it can be restored within 30 minutes.
        // Actual run completion (LootrunFinishedEvent) handles the real cleanup.
        if (currentState == LootrunningState.NOT_RUNNING) {
            if (lastState != LootrunningState.NOT_RUNNING) {
                LOG.info("State -> NOT_RUNNING (was {}), preserving state for potential relog",
                        lastState);
                saveState();
            }
            lastState = currentState;
            return;
        }

        // Log state transitions
        if (currentState != lastState) {
            LOG.info("Lootrun state: {} -> {}", lastState, currentState);
            DiagnosticLog.event(DiagnosticLog.Category.LOOTRUN, "state_change",
                    Map.of("from", String.valueOf(lastState), "to", String.valueOf(currentState)));
        }

        // Entering CHOOSING_BEACON: snapshot current counts for comparison
        if (currentState == LootrunningState.CHOOSING_BEACON && lastState != LootrunningState.CHOOSING_BEACON) {
            try {
                snapshotOrangeCount = Models.Lootrun.getActiveOrangeBeacons();
                snapshotRainbowCount = Models.Lootrun.getActiveRainbowBeacons();
                LOG.info("CHOOSING_BEACON snapshot: orange={}, rainbow={}", snapshotOrangeCount, snapshotRainbowCount);
            } catch (Exception e) {
                LOG.warn("Failed to snapshot beacon counts from API: {}", e.getMessage());
                snapshotOrangeCount = orangeBeacons.size();
                snapshotRainbowCount = rainbowRemaining >= 0 ? 1 : 0;
            }
        }

        // CHOOSING_BEACON -> IN_TASK: a beacon was selected, determine which one
        if (currentState == LootrunningState.IN_TASK && lastState == LootrunningState.CHOOSING_BEACON) {
            handleBeaconSelected();
        }

        // Bootstrap on first active lootrun detection (after relog or mod load)
        if (!bootstrapped) {
            LOG.info("First active lootrun detection, bootstrapping...");
            bootstrapFromSavedState();
            bootstrapped = true;
        }

        // Save state on lootrun state transitions (not just beacon selection)
        if (currentState != lastState && currentState != LootrunningState.NOT_RUNNING) {
            saveState();
        }

        lastState = currentState;

        // Periodic auto-save during active lootruns (every 60s)
        if (hasActiveBeaconData()) {
            long now = System.currentTimeMillis();
            if (now - lastPeriodicSave >= PERIODIC_SAVE_INTERVAL_MS) {
                lastPeriodicSave = now;
                saveState();
            }
            renderOverlay(guiGraphics, mc);
        }
    }

    // ── Wynntils Event Handlers (run completion) ────────────────────────

    @SubscribeEvent
    public void onLootrunCompleted(LootrunFinishedEvent.Completed event) {
        LOG.info("LootrunFinishedEvent.Completed — clearing all beacon state");
        DiagnosticLog.event(DiagnosticLog.Category.LOOTRUN, "run_completed",
                Map.of("orangeBeacons", orangeBeacons.size(),
                        "rainbowRemaining", rainbowRemaining,
                        "totalChoices", beaconChoiceLog.size()));
        clearAll();
        deleteStateFile();
    }

    @SubscribeEvent
    public void onLootrunFailed(LootrunFinishedEvent.Failed event) {
        LOG.info("LootrunFinishedEvent.Failed — clearing all beacon state");
        DiagnosticLog.event(DiagnosticLog.Category.LOOTRUN, "run_failed",
                Map.of("orangeBeacons", orangeBeacons.size(),
                        "rainbowRemaining", rainbowRemaining,
                        "totalChoices", beaconChoiceLog.size()));
        clearAll();
        deleteStateFile();
    }

    /** Returns true if there is any beacon data worth displaying. */
    private boolean hasActiveBeaconData() {
        if (!orangeBeacons.isEmpty()) return true;
        if (rainbowRemaining >= 0) return true;
        for (int count : beaconCounts.values()) {
            if (count > 0) return true;
        }
        return false;
    }

    // ── Beacon Selection Detection (poll-based) ────────────────────────

    /**
     * Called when we detect CHOOSING_BEACON -> IN_TASK transition.
     * Uses Wynntils' getLastTaskBeaconColor() API for precise beacon identification,
     * with fallback to count-comparison for orange/rainbow.
     */
    private void handleBeaconSelected() {
        // Try to use the precise API first
        LootrunBeaconKind selectedKind = null;
        boolean wasVibrant = false;
        int challengeNum = getCurrentChallengeNumber();

        try {
            selectedKind = Models.Lootrun.getLastTaskBeaconColor();
            wasVibrant = Models.Lootrun.wasLastBeaconVibrant();
            LOG.info("Beacon selected via API: kind={}, kindClass={}, vibrant={}, rawEnum={}",
                    selectedKind, selectedKind != null ? selectedKind.getClass().getName() : "null",
                    wasVibrant, selectedKind != null ? selectedKind.name() : "null");
        } catch (Exception e) {
            LOG.warn("getLastTaskBeaconColor() unavailable ({}), falling back to count comparison", e.getMessage());
        }

        // Fallback: if API didn't return a result, use count comparison
        if (selectedKind == null) {
            selectedKind = inferBeaconFromCounts();
            wasVibrant = rainbowRemaining > 0;
        }

        // Record the choice
        if (selectedKind != null) {
            beaconChoiceLog.add(new BeaconChoice(selectedKind, wasVibrant, challengeNum));
            DiagnosticLog.event(DiagnosticLog.Category.BEACON, "beacon_selected",
                    Map.of("type", selectedKind.name(), "vibrant", wasVibrant,
                            "challenge", challengeNum));
        }

        // Capture pre-decrement rainbow state for vibrant calculations
        boolean rainbowWasActive = rainbowRemaining > 0;

        // Decrement existing duration-based beacons (each selection = one challenge consumed)
        decrementBeacons();

        // Process the selected beacon
        if (selectedKind == null) {
            LOG.warn("Could not determine beacon type, consuming aqua only");
            consumeAqua();
        } else {
            processBeaconSelection(selectedKind, wasVibrant, rainbowWasActive);
        }

        LOG.info("  Post state: orange={}, rainbow={}, aquaPending={}, counts={}",
                orangeBeacons, rainbowRemaining, aquaPending, beaconCounts);
        saveState();
    }

    /**
     * Fallback beacon inference using orange/rainbow count comparison.
     * Used when getLastTaskBeaconColor() is not available.
     */
    private LootrunBeaconKind inferBeaconFromCounts() {
        int currentOrange;
        int currentRainbow;
        try {
            currentOrange = Models.Lootrun.getActiveOrangeBeacons();
            currentRainbow = Models.Lootrun.getActiveRainbowBeacons();
        } catch (Exception e) {
            LOG.warn("Failed to read beacon counts from Wynntils: {}", e.getMessage());
            return null;
        }

        LOG.info("Count comparison: orange {} -> {}, rainbow {} -> {}",
                snapshotOrangeCount, currentOrange, snapshotRainbowCount, currentRainbow);

        if (currentOrange > snapshotOrangeCount) {
            return LootrunBeaconKind.ORANGE;
        } else if (currentRainbow > snapshotRainbowCount) {
            return LootrunBeaconKind.RAINBOW;
        } else {
            // Counts unchanged - could be any non-counted beacon type
            // Default to AQUA since it's the most important to track for duration math
            return LootrunBeaconKind.AQUA;
        }
    }

    /**
     * Process a beacon selection and update all tracking state.
     * @param rainbowWasActive whether rainbow was active BEFORE decrement (for vibrant calc)
     */
    private void processBeaconSelection(LootrunBeaconKind kind, boolean wasVibrant, boolean rainbowWasActive) {
        String vibrantMarker = wasVibrant ? " (vibrant)" : "";

        switch (kind) {
            case ORANGE -> {
                boolean isVibrant = wasVibrant || rainbowWasActive;
                int duration = calculateDuration(5, isVibrant);
                orangeBeacons.add(duration);
                LOG.info("  -> ORANGE{}: +{} challenges (aqua={})",
                        vibrantMarker, duration, aquaPending);
                consumeAqua();
            }
            case RAINBOW -> {
                boolean isVibrant = wasVibrant || rainbowWasActive;
                int duration = calculateDuration(10, isVibrant);
                rainbowRemaining = Math.max(rainbowRemaining, 0) + duration;
                LOG.info("  -> RAINBOW{}: +{} (total={})",
                        vibrantMarker, duration, rainbowRemaining);
                consumeAqua();
            }
            case AQUA -> {
                aquaPending = true;
                aquaWasVibrant = wasVibrant || rainbowWasActive;
                LOG.info("  -> AQUA{}: boost pending, vibrant={}",
                        vibrantMarker, aquaWasVibrant);
                // Don't consume aqua - aqua sets it
            }
            case BLUE -> {
                LOG.info("  -> BLUE{}: boon (not tracked)", vibrantMarker);
                consumeAqua();
            }
            case PURPLE -> {
                LOG.info("  -> PURPLE{}: curses/pulls (not tracked)", vibrantMarker);
                consumeAqua();
            }
            case YELLOW -> {
                LOG.info("  -> YELLOW{}: chest (not tracked)", vibrantMarker);
                consumeAqua();
            }
            case CRIMSON -> {
                incrementBeaconCount(LootrunBeaconKind.CRIMSON);
                LOG.info("  -> CRIMSON{}: {} trials",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.CRIMSON));
                consumeAqua();
            }
            case GREEN -> {
                LOG.info("  -> GREEN{}: time bonus (not tracked)", vibrantMarker);
                consumeAqua();
            }
            case DARK_GRAY -> {
                LOG.info("  -> DARK_GRAY{}: used (not tracked)", vibrantMarker);
                consumeAqua();
            }
            case WHITE -> {
                LOG.info("  -> WHITE{}: +5 challenges (not tracked)", vibrantMarker);
                consumeAqua();
            }
            case GRAY -> {
                incrementBeaconCount(LootrunBeaconKind.GRAY);
                LOG.info("  -> GRAY{}: {} missions",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.GRAY));
                consumeAqua();
            }
            case RED -> {
                LOG.info("  -> RED{}: used (not tracked)", vibrantMarker);
                consumeAqua();
            }
            default -> {
                LOG.info("  -> UNKNOWN beacon kind: {}", kind);
                consumeAqua();
            }
        }
    }

    // ── Core Logic ─────────────────────────────────────────────────────

    private int calculateDuration(int baseDuration, boolean isVibrant) {
        int duration = isVibrant ? baseDuration * 2 : baseDuration;
        if (aquaPending) {
            duration *= aquaWasVibrant ? 3 : 2;
        }
        return duration;
    }

    private void decrementBeacons() {
        List<Integer> updated = new ArrayList<>();
        for (int count : orangeBeacons) {
            int remaining = count - 1;
            if (remaining > 0) {
                updated.add(remaining);
            }
        }
        orangeBeacons.clear();
        orangeBeacons.addAll(updated);

        if (rainbowRemaining > 0) {
            rainbowRemaining--;
            if (rainbowRemaining <= 0) {
                rainbowRemaining = -1;
            }
        }
    }

    private void consumeAqua() {
        aquaPending = false;
        aquaWasVibrant = false;
    }

    private void incrementBeaconCount(LootrunBeaconKind kind) {
        beaconCounts.merge(kind, 1, Integer::sum);
    }

    private int getBeaconCount(LootrunBeaconKind kind) {
        return beaconCounts.getOrDefault(kind, 0);
    }

    private int getCurrentChallengeNumber() {
        try {
            return Models.Lootrun.getChallenges().current();
        } catch (Exception e) {
            LOG.warn("getChallenges() failed: {}", e.getMessage());
            return -1;
        }
    }

    private void clearAll() {
        orangeBeacons.clear();
        rainbowRemaining = -1;
        aquaPending = false;
        aquaWasVibrant = false;
        beaconCounts.clear();
        beaconChoiceLog.clear();
        bootstrapped = false;
    }

    // ── State Persistence ──────────────────────────────────────────────

    private static class SavedState {
        List<Integer> orangeBeacons = new ArrayList<>();
        int rainbowRemaining = -1;
        boolean aquaPending = false;
        boolean aquaWasVibrant = false;
        Map<String, Integer> beaconCounts = new LinkedHashMap<>();
        List<BeaconChoice> beaconChoiceLog = new ArrayList<>();
        long savedAt = 0;
    }

    private void saveState() {
        try {
            SavedState state = new SavedState();
            state.orangeBeacons = new ArrayList<>(orangeBeacons);
            state.rainbowRemaining = rainbowRemaining;
            state.aquaPending = aquaPending;
            state.aquaWasVibrant = aquaWasVibrant;
            // Serialize beacon counts as String keys for JSON compatibility
            for (Map.Entry<LootrunBeaconKind, Integer> entry : beaconCounts.entrySet()) {
                state.beaconCounts.put(entry.getKey().name(), entry.getValue());
            }
            state.beaconChoiceLog = new ArrayList<>(beaconChoiceLog);
            state.savedAt = System.currentTimeMillis();

            Files.createDirectories(STATE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(STATE_PATH)) {
                GSON.toJson(state, writer);
            }
        } catch (IOException e) {
            LOG.warn("Failed to save lootrun state: {}", e.getMessage());
        }
    }

    private void deleteStateFile() {
        try {
            Files.deleteIfExists(STATE_PATH);
        } catch (IOException e) {
            LOG.warn("Failed to delete state file: {}", e.getMessage());
        }
    }

    private void bootstrapFromSavedState() {
        if (Files.exists(STATE_PATH)) {
            try (Reader reader = Files.newBufferedReader(STATE_PATH)) {
                SavedState state = GSON.fromJson(reader, SavedState.class);
                if (state != null) {
                    long ageMinutes = (System.currentTimeMillis() - state.savedAt) / 60000;
                    LOG.info("Found saved state ({}m old): orange={}, rainbow={}, " +
                                    "aquaPending={}, counts={}",
                            ageMinutes, state.orangeBeacons, state.rainbowRemaining,
                            state.aquaPending, state.beaconCounts);

                    if (ageMinutes <= 120) {
                        orangeBeacons.clear();
                        orangeBeacons.addAll(state.orangeBeacons);
                        rainbowRemaining = state.rainbowRemaining;
                        aquaPending = state.aquaPending;
                        aquaWasVibrant = state.aquaWasVibrant;

                        // Restore beacon counts
                        beaconCounts.clear();
                        if (state.beaconCounts != null) {
                            for (Map.Entry<String, Integer> entry : state.beaconCounts.entrySet()) {
                                try {
                                    LootrunBeaconKind kind = LootrunBeaconKind.valueOf(entry.getKey());
                                    beaconCounts.put(kind, entry.getValue());
                                } catch (IllegalArgumentException e) {
                                    LOG.warn("Unknown beacon kind in saved state: {}",
                                            entry.getKey());
                                }
                            }
                        }

                        // Restore choice log
                        beaconChoiceLog.clear();
                        if (state.beaconChoiceLog != null) {
                            beaconChoiceLog.addAll(state.beaconChoiceLog);
                        }

                        LOG.info("Restored beacon state from disk");
                        return;
                    } else {
                        LOG.info("Saved state too old ({}m), ignoring", ageMinutes);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to load saved state: {}", e.getMessage());
            }
        }

        // Fallback: bootstrap from Wynntils API
        bootstrapFromWynntils();
    }

    private void bootstrapFromWynntils() {
        LOG.info("Bootstrapping from Wynntils API...");
        try {
            // Bootstrap orange beacons
            int orangeCount = 0;
            try { orangeCount = Models.Lootrun.getActiveOrangeBeacons(); } catch (Exception e) { LOG.warn("getActiveOrangeBeacons() failed: {}", e.getMessage()); }

            if (orangeCount > 0 && orangeBeacons.isEmpty()) {
                int nextExpiry = -1;
                try { nextExpiry = Models.Lootrun.getChallengesTillNextOrangeExpires(); } catch (Exception e) { LOG.warn("getChallengesTillNextOrangeExpires() failed: {}", e.getMessage()); }
                if (nextExpiry > 0) orangeBeacons.add(nextExpiry);
                for (int i = orangeBeacons.size(); i < orangeCount; i++) orangeBeacons.add(-1);
                LOG.info("  Bootstrapped {} orange beacon(s) (first={})", orangeCount, nextExpiry);
            }

            // Bootstrap rainbow beacons
            int rainbowCount = 0;
            try { rainbowCount = Models.Lootrun.getActiveRainbowBeacons(); } catch (Exception e) { LOG.warn("getActiveRainbowBeacons() failed: {}", e.getMessage()); }

            if (rainbowCount > 0 && rainbowRemaining < 0) {
                rainbowRemaining = 0;
                LOG.info("  Bootstrapped rainbow beacon");
            }

            // Bootstrap per-type counts from Wynntils API
            for (LootrunBeaconKind kind : LootrunBeaconKind.values()) {
                // Only bootstrap beacons we actually display on HUD (grey, crimson)
                if (kind != LootrunBeaconKind.GRAY && kind != LootrunBeaconKind.CRIMSON) continue;
                try {
                    int apiCount = Models.Lootrun.getBeaconCount(kind);
                    if (apiCount > 0) {
                        beaconCounts.put(kind, apiCount);
                        LOG.info("  Bootstrapped {} count: {}", kind, apiCount);
                    }
                } catch (Exception e) {
                    LOG.warn("getBeaconCount({}) failed: {}", kind, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Bootstrap from Wynntils failed: {}", e.getMessage());
        }
        LOG.info("Bootstrap result: orange={}, rainbow={}, counts={}",
                orangeBeacons, rainbowRemaining, beaconCounts);
    }

    // ── Rendering ──────────────────────────────────────────────────────

    private void renderOverlay(GuiGraphics guiGraphics, Minecraft mc) {
        // Build lines to render
        List<HudLine> lines = buildHudLines();
        if (lines.isEmpty()) return;

        int boxWidth = 160;
        int lineHeight = 11;
        int headerHeight = 14;
        int boxHeight = headerHeight + lines.size() * lineHeight + 2;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int x = 4;
        int y = (screenHeight - boxHeight) / 2;

        // Background
        guiGraphics.fill(x - 2, y - 2, x + boxWidth, y + boxHeight, 0x80000000);
        guiGraphics.drawString(mc.font, "Beacons", x, y, COLOR_HEADER);
        y += headerHeight;

        // Render each line
        for (HudLine line : lines) {
            guiGraphics.drawString(mc.font, line.text, x + 2, y, line.color);
            y += lineHeight;
        }
    }

    /** A single line in the HUD overlay. */
    private static class HudLine {
        final String text;
        final int color;

        HudLine(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    /**
     * Builds the list of HUD lines to display.
     * Shows active beacon countdowns and cumulative beacon counts.
     */
    private List<HudLine> buildHudLines() {
        List<HudLine> lines = new ArrayList<>();

        // Orange beacons - just show remaining count per beacon
        if (!orangeBeacons.isEmpty()) {
            List<Integer> sorted = new ArrayList<>(orangeBeacons);
            sorted.sort((a, b) -> {
                if (a < 0 && b < 0) return 0;
                if (a < 0) return 1;
                if (b < 0) return -1;
                return Integer.compare(b, a);
            });

            for (int count : sorted) {
                int color = getCountColor(count);
                String text = count < 0 ? "Orange: active" : "Orange: " + count;
                lines.add(new HudLine(text, color));
            }
        }

        // Rainbow
        if (rainbowRemaining >= 0) {
            String text = rainbowRemaining > 0
                    ? "Rainbow: " + rainbowRemaining
                    : "Rainbow: active";
            lines.add(new HudLine(text, BEACON_COLORS.get(LootrunBeaconKind.RAINBOW)));
        }

        // Gray
        int grayCount = getBeaconCount(LootrunBeaconKind.GRAY);
        if (grayCount > 0) {
            lines.add(new HudLine("Grey: " + grayCount + "/3",
                    BEACON_COLORS.get(LootrunBeaconKind.GRAY)));
        }

        // Crimson
        int crimsonCount = getBeaconCount(LootrunBeaconKind.CRIMSON);
        if (crimsonCount > 0) {
            lines.add(new HudLine("Crimson: " + crimsonCount + "/2",
                    BEACON_COLORS.get(LootrunBeaconKind.CRIMSON)));
        }

        return lines;
    }

    private int getCountColor(int remaining) {
        if (remaining < 0) return 0xFF888888;
        if (remaining <= 5) return COLOR_CRITICAL;
        if (remaining <= 15) return COLOR_WARNING;
        return COLOR_HEALTHY;
    }
}
