package com.wynnsort.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ContainerSetContentEvent;
import com.wynntils.models.containers.Container;
import com.wynntils.models.gear.type.GearTier;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.lootrun.event.LootrunFinishedEvent;
import com.wynntils.models.lootrun.type.LootrunningState;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks "dry streak" — the number of lootrun reward pulls since the last mythic drop.
 * Persists data across sessions in config/wynnsort/dry_streak.json.
 *
 * Detection strategy:
 * 1. Subscribe to LootrunFinishedEvent.Completed to get the reward pull count.
 * 2. Subscribe to ContainerSetContentEvent.Post to scan reward chest contents
 *    for mythic-tier gear items (GearTier.MYTHIC).
 * 3. If a mythic is found in the reward chest, reset the dry streak and record it.
 *    Otherwise, add pulls to the running dry streak counter.
 *
 * HUD overlay shows the current dry streak and lifetime stats during active lootruns.
 */
public class DryStreakTracker implements HudRenderCallback {

    public static final DryStreakTracker INSTANCE = new DryStreakTracker();
    private static final FeatureLogger LOG = new FeatureLogger("DryStrk", DiagnosticLog.Category.DRY_STREAK);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("wynnsort/dry_streak.json");

    // HUD color thresholds (based on ~1/2500 mythic rate)
    private static final int COLOR_WHITE  = 0xFFFFFFFF;  // < 1250 pulls (under half expected)
    private static final int COLOR_YELLOW = 0xFFFFFF55;  // 1250-2499 pulls (approaching expected)
    private static final int COLOR_ORANGE = 0xFFFFAA00;  // 2500-4999 pulls (past expected rate)
    private static final int COLOR_RED    = 0xFFFF5555;  // >= 5000 pulls (2x expected rate)
    private static final int COLOR_LABEL  = 0xFFAAAAAA;
    private static final int COLOR_HEADER = 0xFFFF8800;

    /** Whether LootChestDataHelper loaded successfully (Models.LootChest available). */
    private static boolean lootChestAvailable = false;
    private static boolean lootChestChecked = false;

    // ── Persistent data ──────────────────────────────────────────────

    private DryStreakData data = new DryStreakData();

    // ── Transient state ──────────────────────────────────────────────

    /** Pulls from the most recent LootrunFinishedEvent.Completed, pending mythic check. */
    private int pendingPulls = 0;

    /** Whether we detected a mythic in the current lootrun's reward chest(s). */
    private boolean mythicFoundThisRun = false;

    /** Name of the mythic found this run (for recording). */
    private String mythicNameThisRun = null;

    /** Whether data has been loaded from disk. */
    private boolean loaded = false;

    private DryStreakTracker() {}

    // ── Data structure ───────────────────────────────────────────────

    public static class DryStreakData {
        public long totalPullsWithoutMythic = 0;
        public long totalLifetimePulls = 0;
        public int mythicsFound = 0;
        public long lastMythicTimestamp = 0;
        public String lastMythicName = "";
        public long longestDryStreak = 0;
        public int currentRunPulls = 0;
    }

    // ── Public accessors ─────────────────────────────────────────────

    public DryStreakData getData() {
        return data;
    }

    // ── Initialization ───────────────────────────────────────────────

    public void init() {
        load();
    }

    // ── Event Handlers (Wynntils event bus) ──────────────────────────

    /**
     * Fires when a lootrun completes successfully.
     * Records the number of reward pulls and triggers dry streak evaluation.
     */
    @SubscribeEvent
    public void onLootrunCompleted(LootrunFinishedEvent.Completed event) {
        if (!WynnSortConfig.INSTANCE.dryStreakEnabled) return;

        ensureLoaded();

        int rawPulls = event.getRewardPulls();
        int rerolls = event.getRewardRerolls();
        int effectivePulls = rawPulls * (rerolls + 1);
        LOG.info("Lootrun completed: {} raw pulls x {} rerolls = {} effective, {} challenges, {}s elapsed",
                rawPulls, rerolls + 1, effectivePulls, event.getChallengesCompleted(), event.getTimeElapsed());

        // Store effective pulls — mythic detection happens via container scanning
        pendingPulls = effectivePulls;
        data.currentRunPulls = effectivePulls;

        // Update lifetime totals
        data.totalLifetimePulls += effectivePulls;

        // Schedule finalization after a short delay to allow container scan to complete.
        // The container content event fires before/during the lootrun finish event,
        // so by this point we should already know if a mythic was found.
        finalizeLootrun();
    }

    /**
     * Fires when a lootrun fails (timed out, left area, etc.).
     * No reward pulls on failure, so nothing to track.
     */
    @SubscribeEvent
    public void onLootrunFailed(LootrunFinishedEvent.Failed event) {
        LOG.info("Lootrun failed, resetting transient state");
        resetTransientState();
    }

    /**
     * Scans container contents for mythic items.
     * Fires for every container update — we filter to only care about
     * lootrun reward chests.
     */
    @SubscribeEvent
    public void onContainerContent(ContainerSetContentEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.dryStreakEnabled) return;

        // Only scan during active lootruns
        LootrunningState state;
        try {
            state = Models.Lootrun.getState();
        } catch (Exception e) {
            LOG.warn("Models.Lootrun.getState() failed in container scan: {}", e.getMessage());
            return;
        }
        if (state == LootrunningState.NOT_RUNNING) return;

        // Check if this is a lootrun reward chest container
        boolean isRewardChest = false;
        try {
            Container container = Models.Container.getCurrentContainer();
            if (container != null) {
                String containerName = container.getContainerName();
                String className = container.getClass().getSimpleName();
                // Exploratory: log container class/title/slot count for discovery
                LOG.info("Container scan: class={}, fullClass={}, title=\"{}\", id={}",
                        className, container.getClass().getName(), containerName,
                        container.getContainerId());
                isRewardChest = className.contains("LootrunRewardChest")
                        || (containerName != null && containerName.toLowerCase().contains("lootrun reward"));
            }
        } catch (Exception e) {
            LOG.warn("Could not determine container type: {}", e.getMessage());
        }

        if (!isRewardChest) return;

        // Scan items for mythic gear
        List<ItemStack> items = event.getItems();
        if (items == null || items.isEmpty()) return;

        LOG.info("Scanning reward chest: {} slots for mythics", items.size());
        scanForMythics(items);
    }

    // ── Core Logic ───────────────────────────────────────────────────

    /**
     * Scans a list of items (from a reward chest) for mythic-tier gear.
     */
    private void scanForMythics(List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;

            try {
                Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
                if (opt.isEmpty()) continue;

                if (opt.get() instanceof GearItem gearItem) {
                    GearTier tier = gearItem.getGearTier();
                    if (tier == GearTier.MYTHIC) {
                        String name = gearItem.getName();
                        LOG.info("MYTHIC FOUND in reward chest: {} (tier={}, class={})",
                                name, tier.name(), gearItem.getClass().getSimpleName());
                        mythicFoundThisRun = true;
                        mythicNameThisRun = name;
                        Map<String, Object> evtData = new LinkedHashMap<>();
                        evtData.put("name", name);
                        evtData.put("tier", tier.name());
                        LOG.event("mythic_found", evtData);
                        return; // One mythic per run is enough to reset
                    }
                }
            } catch (Exception e) {
                LOG.info("Error scanning item: {}", e.getMessage());
            }
        }
    }

    /**
     * Called after a lootrun completes. Evaluates whether a mythic was found
     * and updates the dry streak accordingly.
     */
    private void finalizeLootrun() {
        if (mythicFoundThisRun) {
            // Mythic found! Record it and reset dry streak
            LOG.info("Mythic detected this run: \"{}\"", mythicNameThisRun);
            LOG.info("Dry streak was: {} pulls", data.totalPullsWithoutMythic);

            // Update longest dry streak if current one is a record
            if (data.totalPullsWithoutMythic > data.longestDryStreak) {
                data.longestDryStreak = data.totalPullsWithoutMythic;
                LOG.info("New longest dry streak record: {} pulls", data.longestDryStreak);
            }

            // Record the mythic
            data.mythicsFound++;
            data.lastMythicTimestamp = System.currentTimeMillis();
            data.lastMythicName = mythicNameThisRun != null ? mythicNameThisRun : "Unknown";

            // Reset dry streak counter
            data.totalPullsWithoutMythic = 0;

        } else {
            // No mythic — extend dry streak
            data.totalPullsWithoutMythic += pendingPulls;
            LOG.info("No mythic this run. Dry streak now: {} pulls (+{})",
                    data.totalPullsWithoutMythic, pendingPulls);

            // Update longest dry streak if current one is a record
            if (data.totalPullsWithoutMythic > data.longestDryStreak) {
                data.longestDryStreak = data.totalPullsWithoutMythic;
            }
        }

        Map<String, Object> streakData = new LinkedHashMap<>();
        streakData.put("currentDryStreak", data.totalPullsWithoutMythic);
        streakData.put("longestDryStreak", data.longestDryStreak);
        streakData.put("totalLifetimePulls", data.totalLifetimePulls);
        streakData.put("mythicsFound", data.mythicsFound);
        streakData.put("mythicThisRun", mythicFoundThisRun);
        LOG.event("dry_streak_updated", streakData);

        save();
        resetTransientState();
    }

    private void resetTransientState() {
        pendingPulls = 0;
        mythicFoundThisRun = false;
        mythicNameThisRun = null;
        data.currentRunPulls = 0;
    }

    // ── HUD Rendering ────────────────────────────────────────────────

    @Override
    public void onHudRender(GuiGraphics guiGraphics, DeltaTracker tickCounter) {
        if (!WynnSortConfig.INSTANCE.dryStreakEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        // Only show during active lootruns
        LootrunningState state;
        try {
            state = Models.Lootrun.getState();
        } catch (Exception e) {
            LOG.warn("Models.Lootrun.getState() failed in HUD render: {}", e.getMessage());
            return;
        }
        if (state == LootrunningState.NOT_RUNNING) return;

        ensureLoaded();
        renderOverlay(guiGraphics, mc);
    }

    private void renderOverlay(GuiGraphics guiGraphics, Minecraft mc) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int padding = 4;
        int lineHeight = mc.font.lineHeight + 2;

        // Build lines to render
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();

        // Regular chest dry streak (from Wynntils LootChest model)
        if (WynnSortConfig.INSTANCE.showDryChests) {
            int dryChests = getWynntilsDryChestCount();
            int dryItems = getWynntilsDryItemCount();

            if (dryChests >= 0) {
                String chestLine = "Dry: " + dryChests + " chests";
                if (dryItems >= 0) {
                    chestLine += " | " + dryItems + " items";
                }
                lines.add(chestLine);
                colors.add(getDryStreakColor(dryChests));
            }
        }

        // Reward pull dry streak (our own tracking)
        if (WynnSortConfig.INSTANCE.showDryPulls) {
            long dryPulls = data.totalPullsWithoutMythic;
            if (dryPulls > 0) {
                lines.add("Pulls: " + dryPulls + " dry");
                colors.add(getDryStreakColor(dryPulls));
            }
        }

        if (lines.isEmpty()) return;

        // Calculate box dimensions
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, mc.font.width(line));
        }
        int boxWidth = maxWidth + padding * 2;
        int boxHeight = lines.size() * lineHeight + padding * 2 - 2;

        // Use configured position or default (bottom-right, above hotbar)
        float cfgX = WynnSortConfig.INSTANCE.dryStreakHudX;
        float cfgY = WynnSortConfig.INSTANCE.dryStreakHudY;
        int x = cfgX >= 0 ? (int) (cfgX * screenWidth) : screenWidth - boxWidth - 4;
        int y = cfgY >= 0 ? (int) (cfgY * screenHeight) : screenHeight - boxHeight - 50;
        x = Math.max(0, Math.min(x, screenWidth - boxWidth));
        y = Math.max(0, Math.min(y, screenHeight - boxHeight));

        // Background
        guiGraphics.fill(x - 2, y - 2, x + boxWidth, y + boxHeight, 0x80000000);

        // Render lines
        int textY = y + padding;
        for (int i = 0; i < lines.size(); i++) {
            guiGraphics.drawString(mc.font, lines.get(i), x + padding, textY, colors.get(i));
            textY += lineHeight;
        }
    }

    public static final double MYTHIC_RATE = 1.0 / 2500.0;

    private int getDryStreakColor(long count) {
        // Thresholds work for both chest counts and pull counts
        // (chests ~= pulls since each chest is roughly 1 item-check for mythic)
        if (count >= 5000) return COLOR_RED;
        if (count >= 2500) return COLOR_ORANGE;
        if (count >= 1250) return COLOR_YELLOW;
        return COLOR_WHITE;
    }

    public static String formatTimeAgo(long timestamp) {
        if (timestamp <= 0) return "unknown";

        long elapsed = System.currentTimeMillis() - timestamp;
        if (elapsed < 0) return "just now";

        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d ago";
        if (hours > 0) return hours + "h ago";
        if (minutes > 0) return minutes + "m ago";
        return "just now";
    }

    // ── Wynntils LootChest Integration ─────────────────────────────

    /**
     * Reads Wynntils' dry chest count (regular loot chests opened without a mythic).
     * Returns -1 if LootChest model is unavailable.
     */
    public int getWynntilsDryChestCount() {
        checkLootChestAvailable();
        if (!lootChestAvailable) return -1;
        try {
            return com.wynnsort.util.LootChestDataHelper.getDryChestCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Reads Wynntils' count of items seen in chests during the dry streak.
     * Sums all gear tiers from Models.LootChest.dryItemTiers.
     * Returns -1 if unavailable.
     */
    public int getWynntilsDryItemCount() {
        checkLootChestAvailable();
        if (!lootChestAvailable) return -1;
        try {
            return com.wynnsort.util.LootChestDataHelper.getDryItemCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void checkLootChestAvailable() {
        if (lootChestChecked) return;
        lootChestChecked = true;
        try {
            com.wynnsort.util.LootChestDataHelper.getDryChestCount();
            lootChestAvailable = true;
            LOG.info("Wynntils LootChest model available — chest/item tracking enabled");
        } catch (Throwable t) {
            lootChestAvailable = false;
            LOG.info("Wynntils LootChest model not available: {}", t.getMessage());
        }
    }

    // ── Persistence ──────────────────────────────────────────────────

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public void load() {
        if (Files.exists(DATA_PATH)) {
            try (Reader reader = Files.newBufferedReader(DATA_PATH)) {
                DryStreakData loaded = GSON.fromJson(reader, DryStreakData.class);
                if (loaded != null) {
                    this.data = loaded;
                    LOG.info("Loaded data: dry={}, lifetime={}, mythics={}, longest={}, lastMythic=\"{}\"",
                            data.totalPullsWithoutMythic, data.totalLifetimePulls,
                            data.mythicsFound, data.longestDryStreak, data.lastMythicName);
                }
            } catch (Exception e) {
                LOG.warn("Failed to load data, using defaults", e);
                this.data = new DryStreakData();
            }
        } else {
            LOG.info("No saved data found, starting fresh");
            this.data = new DryStreakData();
        }
        this.loaded = true;
    }

    public void save() {
        try {
            Files.createDirectories(DATA_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(DATA_PATH)) {
                GSON.toJson(data, writer);
            }
            LOG.info("Saved data to {}", DATA_PATH);
        } catch (IOException e) {
            LOG.error("Failed to save data", e);
        }
    }
}
