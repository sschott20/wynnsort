package com.wynnsort.feature;

import com.wynnsort.SortState;
import com.wynnsort.StatFilter;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynnsort.util.ScoreComputation;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.SlotRenderEvent;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.*;

public class QualityOverlayFeature {

    public static final QualityOverlayFeature INSTANCE = new QualityOverlayFeature();

    private static final FeatureLogger LOG = new FeatureLogger("Overlay", DiagnosticLog.Category.OVERLAY);

    // Quality tier colors (ARGB, 50% alpha)
    private static final int COLOR_POOR = 0x80FF3333;       // 0-29%   red
    private static final int COLOR_BELOW_AVG = 0x80FF9933;  // 30-59%  orange
    private static final int COLOR_AVERAGE = 0x80FFFF33;    // 60-79%  yellow
    private static final int COLOR_GOOD = 0x8033FF33;       // 80-94%  green
    private static final int COLOR_EXCELLENT = 0x8033FFFF;  // 95-100% cyan

    private static final int TEXT_COLOR = 0xFFFFFFFF;

    // Rank badge colors (ARGB, fully opaque)
    private static final int RANK_GOLD   = 0xFFFFD700;  // #1
    private static final int RANK_SILVER = 0xFFC0C0C0;  // #2
    private static final int RANK_BRONZE = 0xFFCD7F32;  // #3

    // Sampling counter for diagnostic logging (log every Nth score computation)
    private int diagnosticSampleCounter = 0;
    private static final int DIAGNOSTIC_SAMPLE_RATE = 50;

    // Per-frame rank computation cache
    private long lastFrameCount = -1;
    /** Maps slot index -> rank (1, 2, or 3). Only top 3 entries. */
    private Map<Integer, Integer> slotRanks = Collections.emptyMap();

    // Logging state
    private boolean firstRenderLogged = false;
    private String lastContainerTitle = null;

    // Circuit breaker: stop retrying after ScoreComputation fails to load
    private boolean scoreComputationBroken = false;

    private QualityOverlayFeature() {}

    @SubscribeEvent
    public void onSlotRender(SlotRenderEvent.Post event) {
        if (!WynnSortConfig.INSTANCE.overlayEnabled) return;
        if (scoreComputationBroken) return;

        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> containerScreen)) return;

        // Detect new container (exploratory: log container title and screen class)
        String containerTitle = containerScreen.getTitle().getString();
        if (!containerTitle.equals(lastContainerTitle)) {
            lastContainerTitle = containerTitle;
            firstRenderLogged = false;
        }

        // Recompute ranks once per frame
        long currentFrame = mc.getFrameTimeNs();
        if (currentFrame != lastFrameCount) {
            lastFrameCount = currentFrame;
            slotRanks = computeRanks(containerScreen);
        }

        Slot slot = event.getSlot();
        ItemStack itemStack = slot.getItem();
        if (itemStack.isEmpty()) return;

        Optional<WynnItem> wynnItemOpt = Models.Item.getWynnItem(itemStack);
        if (wynnItemOpt.isEmpty()) return;
        if (!(wynnItemOpt.get() instanceof GearItem gearItem)) return;

        Optional<GearInstance> gearInstanceOpt = gearItem.getItemInstance();
        if (gearInstanceOpt.isEmpty()) return;

        GearInstance gearInstance = gearInstanceOpt.get();
        float pct;
        try {
            pct = ScoreComputation.computeScore(gearItem, gearInstance, SortState.getFilters());
        } catch (Exception | NoClassDefFoundError e) {
            LOG.error("ScoreComputation failed, disabling overlay until restart: {}", e.getMessage());
            scoreComputationBroken = true;
            return;
        }
        if (Float.isNaN(pct) || pct < 0.0f) return;

        // Log first successful render per container (exploratory: container title, screen class, item info)
        if (!firstRenderLogged) {
            firstRenderLogged = true;
            LOG.info("Overlay active: container='{}', screenClass={}, item={}, score={}%",
                    containerTitle, containerScreen.getClass().getSimpleName(),
                    gearItem.getItemInfo().name(), Math.round(pct));
            LOG.event("overlay_active", Map.of("container", containerTitle, "screenClass", containerScreen.getClass().getSimpleName()));
        }

        // Sampled diagnostic logging (not every frame)
        diagnosticSampleCounter++;
        if (diagnosticSampleCounter >= DIAGNOSTIC_SAMPLE_RATE) {
            diagnosticSampleCounter = 0;
            try {
                String itemName = gearItem.getItemInfo().name();
                DiagnosticLog.event(DiagnosticLog.Category.OVERLAY, "score_computed",
                        Map.of("item", itemName, "score", Math.round(pct)));
            } catch (Exception e) { LOG.warn("Diagnostic sample failed", e); }
        }

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int x = slot.x;
        int y = slot.y;

        // Color fill (z=200 to render above item icons)
        int color = getQualityColor(pct);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0f, 0.0f, 200.0f);
        guiGraphics.fill(x, y, x + 16, y + 16, color);
        guiGraphics.pose().popPose();

        // Percentage text on top
        if (WynnSortConfig.INSTANCE.showPercentageText) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0f, 0.0f, 300.0f);
            String text = String.valueOf(Math.round(pct));
            int textWidth = mc.font.width(text);
            guiGraphics.drawString(mc.font, text, x + 16 - textWidth, y + 8, TEXT_COLOR);
            guiGraphics.pose().popPose();
        }

        // Rank badge
        Integer rank = slotRanks.get(slot.index);
        if (rank != null) {
            renderRankBadge(guiGraphics, mc.font, x, y, rank);
        }
    }

    /**
     * Scans all slots in the container screen and computes a score for each gear item.
     * Returns a map of slot index -> rank (1, 2, or 3) for the top 3.
     */
    private Map<Integer, Integer> computeRanks(AbstractContainerScreen<?> screen) {
        List<StatFilter> filters = SortState.getFilters();
        List<Map.Entry<Integer, Float>> entries = new ArrayList<>();

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            Optional<WynnItem> wynnItemOpt = Models.Item.getWynnItem(stack);
            if (wynnItemOpt.isEmpty()) continue;
            if (!(wynnItemOpt.get() instanceof GearItem gearItem)) continue;

            Optional<GearInstance> gearInstanceOpt = gearItem.getItemInstance();
            if (gearInstanceOpt.isEmpty()) continue;

            float score;
            try {
                score = ScoreComputation.computeScore(gearItem, gearInstanceOpt.get(), filters);
            } catch (Exception | NoClassDefFoundError e) {
                LOG.error("ScoreComputation failed in computeRanks, disabling overlay: {}", e.getMessage());
                scoreComputationBroken = true;
                return Collections.emptyMap();
            }
            if (!Float.isNaN(score) && score >= 0.0f) {
                entries.add(Map.entry(slot.index, score));
            }
        }

        // Need at least 2 items for ranking to be meaningful
        if (entries.size() < 2) {
            return Collections.emptyMap();
        }

        // Sort descending by score
        entries.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

        Map<Integer, Integer> ranks = new HashMap<>();
        for (int i = 0; i < Math.min(3, entries.size()); i++) {
            ranks.put(entries.get(i).getKey(), i + 1);
        }
        return ranks;
    }

    private void renderRankBadge(GuiGraphics guiGraphics, Font font, int x, int y, int rank) {
        String label = "#" + rank;
        int color = switch (rank) {
            case 1 -> RANK_GOLD;
            case 2 -> RANK_SILVER;
            case 3 -> RANK_BRONZE;
            default -> TEXT_COLOR;
        };

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0f, 0.0f, 300.0f);
        guiGraphics.drawString(font, label, x, y, color);
        guiGraphics.pose().popPose();
    }

    private int getQualityColor(float percentage) {
        if (percentage >= 95.0f) return COLOR_EXCELLENT;
        if (percentage >= 80.0f) return COLOR_GOOD;
        if (percentage >= 60.0f) return COLOR_AVERAGE;
        if (percentage >= 30.0f) return COLOR_BELOW_AVG;
        return COLOR_POOR;
    }
}
