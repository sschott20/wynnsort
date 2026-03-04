package com.wynnsort.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynntils.core.components.Models;
import com.wynntils.models.lootrun.type.LootrunningState;
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
import java.util.List;

/**
 * Tracks active rainbow and orange beacon buffs during lootruns and renders
 * a HUD overlay showing each beacon's remaining challenge count.
 *
 * Uses a poll-based approach: monitors Wynntils' simple API getters each tick
 * and detects beacon selections via state transitions + count changes.
 * Does NOT use Wynntils' event bus (which can crash and swallow events).
 *
 * Beacon durations (Base / Vibrant / Vibrant+Aqua):
 *   Orange: 5 / 10 / 30 challenges
 *   Rainbow: 10 / 20 / 60 challenges
 *
 * Vibrant = rainbow is active (doubles base effect).
 * Aqua = aqua beacon was selected immediately before (base aqua: +100%, vibrant aqua: +200%).
 */
public class LootrunBeaconTracker implements HudRenderCallback {

    public static final LootrunBeaconTracker INSTANCE = new LootrunBeaconTracker();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STATE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("wynnsort-lootrun-state.json");

    private static final int COLOR_HEADER = 0xFFFF8800;
    private static final int COLOR_RAINBOW = 0xFFFF44FF;   // magenta
    private static final int COLOR_HEALTHY = 0xFF44FF44;   // green  (>15 remaining)
    private static final int COLOR_WARNING = 0xFFFFAA00;   // orange (6-15 remaining)
    private static final int COLOR_CRITICAL = 0xFFFF4444;  // red    (<=5 remaining)

    // ── Our own beacon tracking ───────────────────────────────────────

    /** Remaining challenge count for each active orange beacon. */
    private final List<Integer> orangeBeacons = new ArrayList<>();

    /** Remaining challenges for rainbow effect, -1 = not active. */
    private int rainbowRemaining = -1;

    /** True if the previous beacon was aqua (boosts the NEXT beacon). */
    private boolean aquaPending = false;

    /** True if the aqua beacon was vibrant (under rainbow -> +200% instead of +100%). */
    private boolean aquaWasVibrant = false;

    // ── Poll-based state detection ────────────────────────────────────

    /** Last observed lootrun state. */
    private LootrunningState lastState = LootrunningState.NOT_RUNNING;

    /** Whether we've bootstrapped from saved state on first lootrun detection. */
    private boolean bootstrapped = false;

    /** Snapshot of Wynntils orange beacon count when entering CHOOSING_BEACON. */
    private int snapshotOrangeCount = 0;

    /** Snapshot of Wynntils rainbow beacon count when entering CHOOSING_BEACON. */
    private int snapshotRainbowCount = 0;

    private LootrunBeaconTracker() {}

    // ── HUD Rendering + Poll Logic ────────────────────────────────────

    /** Tick counter for periodic state logging. */
    private int hudLogTick = 0;

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
            WynnSortMod.log("[WynnSort] HUD tick: lootrunState={}, lastState={}, orangeBeacons={}, rainbowRemaining={}, aquaPending={}, bootstrapped={}",
                    currentState, lastState, orangeBeacons, rainbowRemaining, aquaPending, bootstrapped);
        }

        // Clear on lootrun end
        if (currentState == LootrunningState.NOT_RUNNING) {
            if (lastState != LootrunningState.NOT_RUNNING) {
                WynnSortMod.log("[WynnSort] Lootrun ended (was {}), clearing all", lastState);
                clearAll();
                deleteStateFile();
            }
            lastState = currentState;
            return;
        }

        // Log state transitions
        if (currentState != lastState) {
            WynnSortMod.log("[WynnSort] Lootrun state: {} -> {}", lastState, currentState);
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

        // CHOOSING_BEACON -> IN_TASK: a beacon was selected, infer which one
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

        if (orangeBeacons.isEmpty() && rainbowRemaining < 0) return;

        renderOverlay(guiGraphics, mc);
    }

    // ── Beacon Selection Detection (poll-based) ───────────────────────

    /**
     * Called when we detect CHOOSING_BEACON -> IN_TASK transition.
     * Compares Wynntils API counts before/after to determine which beacon
     * was selected, then updates our tracking state.
     */
    private void handleBeaconSelected() {
        int currentOrange;
        int currentRainbow;
        try {
            currentOrange = Models.Lootrun.getActiveOrangeBeacons();
            currentRainbow = Models.Lootrun.getActiveRainbowBeacons();
        } catch (Exception e) {
            WynnSortMod.logWarn("[WynnSort] Failed to read beacon counts from Wynntils", e);
            // Can't determine beacon type, just decrement
            decrementBeacons();
            consumeAqua();
            saveState();
            return;
        }

        WynnSortMod.log("[WynnSort] Beacon selected! Snapshot: orange={}, rainbow={}. Now: orange={}, rainbow={}",
                snapshotOrangeCount, snapshotRainbowCount, currentOrange, currentRainbow);

        // Decrement existing beacons first (each selection = one challenge consumed)
        decrementBeacons();

        if (currentOrange > snapshotOrangeCount) {
            // Orange beacon selected
            boolean isVibrant = rainbowRemaining > 0;
            int duration = calculateDuration(5, isVibrant);
            orangeBeacons.add(duration);
            WynnSortMod.log("[WynnSort]   -> ORANGE: +{} challenges (vibrant={}, aqua={})", duration, isVibrant, aquaPending);
            consumeAqua();

        } else if (currentRainbow > snapshotRainbowCount) {
            // Rainbow beacon selected
            boolean isVibrant = rainbowRemaining > 0;
            int duration = calculateDuration(10, isVibrant);
            rainbowRemaining = Math.max(rainbowRemaining, 0) + duration;
            WynnSortMod.log("[WynnSort]   -> RAINBOW: +{} (total={})", duration, rainbowRemaining);
            consumeAqua();

        } else if (currentOrange == snapshotOrangeCount && currentRainbow == snapshotRainbowCount) {
            // Counts unchanged — aqua, yellow, purple, or other non-counted beacon
            // Check if aqua is likely: aqua doesn't increase counts but boosts next beacon
            // Yellow/purple also don't increase counts, but aqua is the important one to track
            aquaPending = true;
            aquaWasVibrant = rainbowRemaining > 0;
            WynnSortMod.log("[WynnSort]   -> AQUA (inferred): aquaPending=true, vibrant={}", aquaWasVibrant);

        } else {
            // Counts decreased or other unexpected change — just consume aqua
            WynnSortMod.logWarn("[WynnSort]   -> UNKNOWN change: orange {} -> {}, rainbow {} -> {}",
                    snapshotOrangeCount, currentOrange, snapshotRainbowCount, currentRainbow);
            consumeAqua();
        }

        WynnSortMod.log("[WynnSort]   Post state: orange={}, rainbow={}, aquaPending={}", orangeBeacons, rainbowRemaining, aquaPending);
        saveState();
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

    private void clearAll() {
        orangeBeacons.clear();
        rainbowRemaining = -1;
        aquaPending = false;
        aquaWasVibrant = false;
        bootstrapped = false;
    }

    // ── State Persistence ─────────────────────────────────────────────

    private static class SavedState {
        List<Integer> orangeBeacons = new ArrayList<>();
        int rainbowRemaining = -1;
        boolean aquaPending = false;
        boolean aquaWasVibrant = false;
        long savedAt = 0;
    }

    private void saveState() {
        try {
            SavedState state = new SavedState();
            state.orangeBeacons = new ArrayList<>(orangeBeacons);
            state.rainbowRemaining = rainbowRemaining;
            state.aquaPending = aquaPending;
            state.aquaWasVibrant = aquaWasVibrant;
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
                    WynnSortMod.log("[WynnSort] Found saved state ({}m old): orange={}, rainbow={}, aquaPending={}",
                            ageMinutes, state.orangeBeacons, state.rainbowRemaining, state.aquaPending);

                    if (ageMinutes <= 30) {
                        orangeBeacons.clear();
                        orangeBeacons.addAll(state.orangeBeacons);
                        rainbowRemaining = state.rainbowRemaining;
                        aquaPending = state.aquaPending;
                        aquaWasVibrant = state.aquaWasVibrant;
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
            int orangeCount = 0;
            try { orangeCount = Models.Lootrun.getActiveOrangeBeacons(); } catch (Exception e) { /* ignore */ }

            if (orangeCount > 0 && orangeBeacons.isEmpty()) {
                int nextExpiry = -1;
                try { nextExpiry = Models.Lootrun.getChallengesTillNextOrangeExpires(); } catch (Exception e) { /* ignore */ }
                if (nextExpiry > 0) orangeBeacons.add(nextExpiry);
                for (int i = orangeBeacons.size(); i < orangeCount; i++) orangeBeacons.add(-1);
                WynnSortMod.log("[WynnSort]   Bootstrapped {} orange beacon(s) (first={})", orangeCount, nextExpiry);
            }

            int rainbowCount = 0;
            try { rainbowCount = Models.Lootrun.getActiveRainbowBeacons(); } catch (Exception e) { /* ignore */ }

            if (rainbowCount > 0 && rainbowRemaining < 0) {
                rainbowRemaining = 0;
                WynnSortMod.log("[WynnSort]   Bootstrapped rainbow beacon");
            }
        } catch (Exception e) {
            WynnSortMod.logWarn("[WynnSort] Bootstrap from Wynntils failed", e);
        }
        WynnSortMod.log("[WynnSort] Bootstrap result: orange={}, rainbow={}", orangeBeacons, rainbowRemaining);
    }

    // ── Rendering ──────────────────────────────────────────────────────

    private void renderOverlay(GuiGraphics guiGraphics, Minecraft mc) {
        int itemCount = orangeBeacons.size() + (rainbowRemaining >= 0 ? 1 : 0);
        if (itemCount == 0) return;

        int boxWidth = 140;
        int lineHeight = 11;
        int headerHeight = 14;
        int boxHeight = headerHeight + itemCount * lineHeight + 2;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int x = 4;
        int y = (screenHeight - boxHeight) / 2;

        guiGraphics.fill(x - 2, y - 2, x + boxWidth, y + boxHeight, 0x80000000);
        guiGraphics.drawString(mc.font, "Lootrun Beacons", x, y, COLOR_HEADER);
        y += headerHeight;

        if (rainbowRemaining >= 0) {
            String text = rainbowRemaining > 0 ? "Rainbow: +" + rainbowRemaining : "Rainbow: active";
            guiGraphics.drawString(mc.font, text, x + 2, y, COLOR_RAINBOW);
            y += lineHeight;
        }

        List<Integer> sorted = new ArrayList<>(orangeBeacons);
        sorted.sort((a, b) -> {
            if (a < 0 && b < 0) return 0;
            if (a < 0) return 1;
            if (b < 0) return -1;
            return Integer.compare(b, a);
        });

        for (int count : sorted) {
            int color = getCountColor(count);
            String text = count < 0 ? "Orange: active" : "Orange: +" + count;
            guiGraphics.drawString(mc.font, text, x + 2, y, color);
            y += lineHeight;
        }
    }

    private int getCountColor(int remaining) {
        if (remaining < 0) return 0xFF888888;
        if (remaining <= 5) return COLOR_CRITICAL;
        if (remaining <= 15) return COLOR_WARNING;
        return COLOR_HEALTHY;
    }
}
