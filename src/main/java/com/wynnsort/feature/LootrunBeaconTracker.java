package com.wynnsort.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynntils.core.components.Models;
import com.wynntils.models.lootrun.beacons.LootrunBeaconKind;
import com.wynntils.models.lootrun.type.LootrunningState;
import com.wynntils.utils.colors.CustomColor;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

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

    /** Human-readable names for display. */
    private static final Map<LootrunBeaconKind, String> BEACON_NAMES = new EnumMap<>(LootrunBeaconKind.class);
    static {
        BEACON_NAMES.put(LootrunBeaconKind.GREEN,     "Green");
        BEACON_NAMES.put(LootrunBeaconKind.YELLOW,    "Yellow");
        BEACON_NAMES.put(LootrunBeaconKind.BLUE,      "Blue");
        BEACON_NAMES.put(LootrunBeaconKind.PURPLE,    "Purple");
        BEACON_NAMES.put(LootrunBeaconKind.GRAY,      "Grey");
        BEACON_NAMES.put(LootrunBeaconKind.ORANGE,    "Orange");
        BEACON_NAMES.put(LootrunBeaconKind.RED,       "Red");
        BEACON_NAMES.put(LootrunBeaconKind.DARK_GRAY, "Dark Grey");
        BEACON_NAMES.put(LootrunBeaconKind.WHITE,     "White");
        BEACON_NAMES.put(LootrunBeaconKind.AQUA,      "Aqua");
        BEACON_NAMES.put(LootrunBeaconKind.CRIMSON,   "Crimson");
        BEACON_NAMES.put(LootrunBeaconKind.RAINBOW,   "Rainbow");
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

    /** Tick counter for periodic state logging. */
    private int hudLogTick = 0;

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
            return;
        }

        // Log state periodically (~5 seconds)
        hudLogTick++;
        if (hudLogTick >= 100) {
            hudLogTick = 0;
            WynnSortMod.log("[WynnSort] HUD tick: lootrunState={}, lastState={}, orangeBeacons={}, " +
                            "rainbowRemaining={}, aquaPending={}, beaconCounts={}, bootstrapped={}",
                    currentState, lastState, orangeBeacons, rainbowRemaining,
                    aquaPending, beaconCounts, bootstrapped);
        }

        // Clear on lootrun end
        if (currentState == LootrunningState.NOT_RUNNING) {
            if (lastState != LootrunningState.NOT_RUNNING) {
                WynnSortMod.log("[WynnSort] Lootrun ended (was {}), clearing all", lastState);
                DiagnosticLog.event(DiagnosticLog.Category.LOOTRUN, "run_completed",
                        Map.of("orangeBeacons", orangeBeacons.size(),
                                "rainbowRemaining", rainbowRemaining,
                                "totalChoices", beaconChoiceLog.size()));
                clearAll();
                deleteStateFile();
            }
            lastState = currentState;
            return;
        }

        // Log state transitions
        if (currentState != lastState) {
            WynnSortMod.log("[WynnSort] Lootrun state: {} -> {}", lastState, currentState);
            DiagnosticLog.event(DiagnosticLog.Category.LOOTRUN, "state_change",
                    Map.of("from", String.valueOf(lastState), "to", String.valueOf(currentState)));
        }

        // Entering CHOOSING_BEACON: snapshot current counts for comparison
        if (currentState == LootrunningState.CHOOSING_BEACON && lastState != LootrunningState.CHOOSING_BEACON) {
            try {
                snapshotOrangeCount = Models.Lootrun.getActiveOrangeBeacons();
                snapshotRainbowCount = Models.Lootrun.getActiveRainbowBeacons();
            } catch (Exception e) {
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
            WynnSortMod.log("[WynnSort] First active lootrun detection, bootstrapping...");
            bootstrapFromSavedState();
            bootstrapped = true;
        }

        lastState = currentState;

        // Render if we have any active beacon data
        if (hasActiveBeaconData()) {
            renderOverlay(guiGraphics, mc);
        }
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
            WynnSortMod.log("[WynnSort] Beacon selected via API: kind={}, vibrant={}",
                    selectedKind, wasVibrant);
        } catch (Exception e) {
            WynnSortMod.log("[WynnSort] getLastTaskBeaconColor() unavailable, falling back to count comparison");
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

        // Decrement existing duration-based beacons (each selection = one challenge consumed)
        decrementBeacons();

        // Process the selected beacon
        if (selectedKind == null) {
            WynnSortMod.logWarn("[WynnSort] Could not determine beacon type, consuming aqua only");
            consumeAqua();
        } else {
            processBeaconSelection(selectedKind, wasVibrant);
        }

        WynnSortMod.log("[WynnSort]   Post state: orange={}, rainbow={}, aquaPending={}, counts={}",
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
            WynnSortMod.logWarn("[WynnSort] Failed to read beacon counts from Wynntils", e);
            return null;
        }

        WynnSortMod.log("[WynnSort] Count comparison: orange {} -> {}, rainbow {} -> {}",
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
     */
    private void processBeaconSelection(LootrunBeaconKind kind, boolean wasVibrant) {
        String vibrantMarker = wasVibrant ? " (vibrant)" : "";

        switch (kind) {
            case ORANGE -> {
                boolean isVibrant = wasVibrant || rainbowRemaining > 0;
                int duration = calculateDuration(5, isVibrant);
                orangeBeacons.add(duration);
                WynnSortMod.log("[WynnSort]   -> ORANGE{}: +{} challenges (aqua={})",
                        vibrantMarker, duration, aquaPending);
                consumeAqua();
            }
            case RAINBOW -> {
                boolean isVibrant = wasVibrant || rainbowRemaining > 0;
                int duration = calculateDuration(10, isVibrant);
                rainbowRemaining = Math.max(rainbowRemaining, 0) + duration;
                WynnSortMod.log("[WynnSort]   -> RAINBOW{}: +{} (total={})",
                        vibrantMarker, duration, rainbowRemaining);
                consumeAqua();
            }
            case AQUA -> {
                incrementBeaconCount(LootrunBeaconKind.AQUA);
                aquaPending = true;
                aquaWasVibrant = wasVibrant || rainbowRemaining > 0;
                WynnSortMod.log("[WynnSort]   -> AQUA{}: boost pending, vibrant={}",
                        vibrantMarker, aquaWasVibrant);
                // Don't consume aqua - aqua sets it
            }
            case BLUE -> {
                incrementBeaconCount(LootrunBeaconKind.BLUE);
                WynnSortMod.log("[WynnSort]   -> BLUE{}: {} boons",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.BLUE));
                consumeAqua();
            }
            case PURPLE -> {
                incrementBeaconCount(LootrunBeaconKind.PURPLE);
                WynnSortMod.log("[WynnSort]   -> PURPLE{}: {} curses/pulls",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.PURPLE));
                consumeAqua();
            }
            case YELLOW -> {
                incrementBeaconCount(LootrunBeaconKind.YELLOW);
                WynnSortMod.log("[WynnSort]   -> YELLOW{}: {} chests",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.YELLOW));
                consumeAqua();
            }
            case CRIMSON -> {
                incrementBeaconCount(LootrunBeaconKind.CRIMSON);
                WynnSortMod.log("[WynnSort]   -> CRIMSON{}: {} trials",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.CRIMSON));
                consumeAqua();
            }
            case GREEN -> {
                incrementBeaconCount(LootrunBeaconKind.GREEN);
                WynnSortMod.log("[WynnSort]   -> GREEN{}: {} uses",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.GREEN));
                consumeAqua();
            }
            case DARK_GRAY -> {
                incrementBeaconCount(LootrunBeaconKind.DARK_GRAY);
                WynnSortMod.log("[WynnSort]   -> DARK_GRAY{}: used (max 1/run)",
                        vibrantMarker);
                consumeAqua();
            }
            case WHITE -> {
                incrementBeaconCount(LootrunBeaconKind.WHITE);
                WynnSortMod.log("[WynnSort]   -> WHITE{}: +5 challenges",
                        vibrantMarker);
                consumeAqua();
            }
            case GRAY -> {
                incrementBeaconCount(LootrunBeaconKind.GRAY);
                WynnSortMod.log("[WynnSort]   -> GRAY{}: {} missions",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.GRAY));
                consumeAqua();
            }
            case RED -> {
                incrementBeaconCount(LootrunBeaconKind.RED);
                WynnSortMod.log("[WynnSort]   -> RED{}: {} uses",
                        vibrantMarker, getBeaconCount(LootrunBeaconKind.RED));
                consumeAqua();
            }
            default -> {
                WynnSortMod.log("[WynnSort]   -> UNKNOWN beacon kind: {}", kind);
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
            WynnSortMod.logWarn("[WynnSort] Failed to save lootrun state", e);
        }
    }

    private void deleteStateFile() {
        try {
            Files.deleteIfExists(STATE_PATH);
        } catch (IOException e) {
            // ignore
        }
    }

    private void bootstrapFromSavedState() {
        if (Files.exists(STATE_PATH)) {
            try (Reader reader = Files.newBufferedReader(STATE_PATH)) {
                SavedState state = GSON.fromJson(reader, SavedState.class);
                if (state != null) {
                    long ageMinutes = (System.currentTimeMillis() - state.savedAt) / 60000;
                    WynnSortMod.log("[WynnSort] Found saved state ({}m old): orange={}, rainbow={}, " +
                                    "aquaPending={}, counts={}",
                            ageMinutes, state.orangeBeacons, state.rainbowRemaining,
                            state.aquaPending, state.beaconCounts);

                    if (ageMinutes <= 30) {
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
                                    WynnSortMod.logWarn("[WynnSort] Unknown beacon kind in saved state: {}",
                                            entry.getKey());
                                }
                            }
                        }

                        // Restore choice log
                        beaconChoiceLog.clear();
                        if (state.beaconChoiceLog != null) {
                            beaconChoiceLog.addAll(state.beaconChoiceLog);
                        }

                        WynnSortMod.log("[WynnSort] Restored beacon state from disk");
                        return;
                    } else {
                        WynnSortMod.log("[WynnSort] Saved state too old ({}m), ignoring", ageMinutes);
                    }
                }
            } catch (Exception e) {
                WynnSortMod.logWarn("[WynnSort] Failed to load saved state", e);
            }
        }

        // Fallback: bootstrap from Wynntils API
        bootstrapFromWynntils();
    }

    private void bootstrapFromWynntils() {
        WynnSortMod.log("[WynnSort] Bootstrapping from Wynntils API...");
        try {
            // Bootstrap orange beacons
            int orangeCount = 0;
            try { orangeCount = Models.Lootrun.getActiveOrangeBeacons(); } catch (Exception e) { /* ignore */ }

            if (orangeCount > 0 && orangeBeacons.isEmpty()) {
                int nextExpiry = -1;
                try { nextExpiry = Models.Lootrun.getChallengesTillNextOrangeExpires(); } catch (Exception e) { /* ignore */ }
                if (nextExpiry > 0) orangeBeacons.add(nextExpiry);
                for (int i = orangeBeacons.size(); i < orangeCount; i++) orangeBeacons.add(-1);
                WynnSortMod.log("[WynnSort]   Bootstrapped {} orange beacon(s) (first={})", orangeCount, nextExpiry);
            }

            // Bootstrap rainbow beacons
            int rainbowCount = 0;
            try { rainbowCount = Models.Lootrun.getActiveRainbowBeacons(); } catch (Exception e) { /* ignore */ }

            if (rainbowCount > 0 && rainbowRemaining < 0) {
                rainbowRemaining = 0;
                WynnSortMod.log("[WynnSort]   Bootstrapped rainbow beacon");
            }

            // Bootstrap per-type counts from Wynntils API
            for (LootrunBeaconKind kind : LootrunBeaconKind.values()) {
                if (kind == LootrunBeaconKind.ORANGE || kind == LootrunBeaconKind.RAINBOW) continue;
                try {
                    int apiCount = Models.Lootrun.getBeaconCount(kind);
                    if (apiCount > 0) {
                        beaconCounts.put(kind, apiCount);
                        WynnSortMod.log("[WynnSort]   Bootstrapped {} count: {}", kind, apiCount);
                    }
                } catch (Exception e) {
                    // API may not support all kinds, ignore
                }
            }
        } catch (Exception e) {
            WynnSortMod.logWarn("[WynnSort] Bootstrap from Wynntils failed", e);
        }
        WynnSortMod.log("[WynnSort] Bootstrap result: orange={}, rainbow={}, counts={}",
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
        guiGraphics.drawString(mc.font, "Lootrun Beacons", x, y, COLOR_HEADER);
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
     * Builds the list of HUD lines to display, ordered by importance:
     * 1. Rainbow (duration-based, most impactful)
     * 2. Orange beacons (duration-based)
     * 3. Count-based beacons in a consistent order
     */
    private List<HudLine> buildHudLines() {
        List<HudLine> lines = new ArrayList<>();

        // Rainbow - duration based
        if (rainbowRemaining >= 0) {
            String vibrantMark = isVibrantFromLog(LootrunBeaconKind.RAINBOW) ? "\u2605" : "";
            String text = rainbowRemaining > 0
                    ? vibrantMark + "Rainbow: +" + rainbowRemaining + " challenges"
                    : vibrantMark + "Rainbow: active";
            lines.add(new HudLine(text, BEACON_COLORS.get(LootrunBeaconKind.RAINBOW)));
        }

        // Orange beacons - duration based, sorted descending
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
                String text = count < 0 ? "Orange: active" : "Orange: +" + count + " challenges";
                lines.add(new HudLine(text, color));
            }
        }

        // Count-based beacons in display order
        LootrunBeaconKind[] countOrder = {
                LootrunBeaconKind.PURPLE,
                LootrunBeaconKind.BLUE,
                LootrunBeaconKind.YELLOW,
                LootrunBeaconKind.CRIMSON,
                LootrunBeaconKind.AQUA,
                LootrunBeaconKind.GREEN,
                LootrunBeaconKind.RED,
                LootrunBeaconKind.GRAY,
                LootrunBeaconKind.DARK_GRAY,
                LootrunBeaconKind.WHITE,
        };

        for (LootrunBeaconKind kind : countOrder) {
            int count = getBeaconCount(kind);
            if (count <= 0) continue;

            String vibrantMark = isVibrantFromLog(kind) ? "\u2605" : "";
            String name = BEACON_NAMES.getOrDefault(kind, kind.name());
            int color = BEACON_COLORS.getOrDefault(kind, 0xFFAAAAAA);
            String text = formatBeaconLine(kind, name, count, vibrantMark);
            lines.add(new HudLine(text, color));
        }

        return lines;
    }

    /**
     * Format a count-based beacon line with type-specific descriptions.
     */
    private String formatBeaconLine(LootrunBeaconKind kind, String name, int count, String vibrantMark) {
        return switch (kind) {
            case BLUE -> vibrantMark + name + ": " + count + " boons";
            case PURPLE -> vibrantMark + name + ": " + count + "x (+%d pulls)".formatted(count);
            case YELLOW -> vibrantMark + name + ": " + count + " chests";
            case CRIMSON -> vibrantMark + name + ": " + count + " trials";
            case AQUA -> vibrantMark + name + ": " + count + "x boosts";
            case GREEN -> vibrantMark + name + ": " + count + "x used";
            case DARK_GRAY -> vibrantMark + name + ": used (+3c +3p)";
            case WHITE -> vibrantMark + name + ": used (+5 chall.)";
            case GRAY -> vibrantMark + name + ": " + count + " missions";
            case RED -> vibrantMark + name + ": " + count + "x used";
            default -> vibrantMark + name + ": " + count + "x";
        };
    }

    /**
     * Check if the most recent choice of a given beacon type was vibrant,
     * by looking through the choice log in reverse.
     */
    private boolean isVibrantFromLog(LootrunBeaconKind kind) {
        for (int i = beaconChoiceLog.size() - 1; i >= 0; i--) {
            BeaconChoice choice = beaconChoiceLog.get(i);
            if (kind.name().equals(choice.beaconType)) {
                return choice.vibrant;
            }
        }
        return false;
    }

    private int getCountColor(int remaining) {
        if (remaining < 0) return 0xFF888888;
        if (remaining <= 5) return COLOR_CRITICAL;
        if (remaining <= 15) return COLOR_WARNING;
        return COLOR_HEALTHY;
    }
}
