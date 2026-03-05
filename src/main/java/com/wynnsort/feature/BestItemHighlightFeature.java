package com.wynnsort.feature;

import com.wynnsort.SortState;
import com.wynnsort.StatFilter;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import com.wynnsort.util.ScoreComputation;
import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ContainerRenderEvent;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * Draws a 1px gold border around the slot of the highest-scoring gear item
 * in the container, based on the current SortState mode.
 */
public class BestItemHighlightFeature {

    public static final BestItemHighlightFeature INSTANCE = new BestItemHighlightFeature();

    private static final FeatureLogger LOG = new FeatureLogger("Highlight", DiagnosticLog.Category.HIGHLIGHT);
    private static final int GOLD_BORDER_COLOR = 0xFFFFD700;

    // Cached reflection fields for AbstractContainerScreen.leftPos / topPos
    private static Field leftPosField;
    private static Field topPosField;
    private static boolean fieldsResolved = false;

    private int lastBestSlotIndex = -1;

    private BestItemHighlightFeature() {}

    @SubscribeEvent
    public void onContainerRender(ContainerRenderEvent event) {
        try {
            if (!WynnSortConfig.INSTANCE.overlayEnabled) {
                return;
            }

            AbstractContainerScreen<?> screen = event.getScreen();
            GuiGraphics guiGraphics = event.getGuiGraphics();
            List<StatFilter> filters = SortState.getFilters();

            // Find the best slot by scanning all slots
            Slot bestSlot = null;
            float bestScore = Float.NEGATIVE_INFINITY;

            for (Slot slot : screen.getMenu().slots) {
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;

                Optional<WynnItem> wynnItemOpt = Models.Item.getWynnItem(stack);
                if (wynnItemOpt.isEmpty()) continue;
                if (!(wynnItemOpt.get() instanceof GearItem gearItem)) continue;

                Optional<GearInstance> gearInstanceOpt = gearItem.getItemInstance();
                if (gearInstanceOpt.isEmpty()) continue;

                GearInstance gearInstance = gearInstanceOpt.get();
                float score = ScoreComputation.computeScore(gearItem, gearInstance, filters);

                if (!Float.isNaN(score) && score > bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }

            if (bestSlot == null) {
                return;
            }

            // Log when best item changes (exploratory: includes screen class name)
            if (bestSlot.index != lastBestSlotIndex) {
                lastBestSlotIndex = bestSlot.index;
                LOG.info("Best item: slot={}, score={}%, screen={}", bestSlot.index, Math.round(bestScore), screen.getClass().getSimpleName());
            }

            // Slot x/y are relative to the container's leftPos/topPos
            int containerLeft = getLeftPos(screen);
            int containerTop = getTopPos(screen);
            if (containerLeft == Integer.MIN_VALUE || containerTop == Integer.MIN_VALUE) {
                return;
            }

            int x = bestSlot.x + containerLeft;
            int y = bestSlot.y + containerTop;
            drawBorder(guiGraphics, x, y, 16, 16, GOLD_BORDER_COLOR);
        } catch (Exception e) {
            LOG.error("Error in highlight render", e);
        }
    }

    /**
     * Draws a 1px border rectangle around the given area.
     */
    private void drawBorder(GuiGraphics guiGraphics, int x, int y, int width, int height, int color) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0f, 0.0f, 310.0f);

        // Top edge
        guiGraphics.fill(x - 1, y - 1, x + width + 1, y, color);
        // Bottom edge
        guiGraphics.fill(x - 1, y + height, x + width + 1, y + height + 1, color);
        // Left edge
        guiGraphics.fill(x - 1, y, x, y + height, color);
        // Right edge
        guiGraphics.fill(x + width, y, x + width + 1, y + height, color);

        guiGraphics.pose().popPose();
    }

    // --- Reflection helpers for accessing protected leftPos/topPos ---

    private static void resolveFields() {
        fieldsResolved = true;
        try {
            leftPosField = AbstractContainerScreen.class.getDeclaredField("leftPos");
            leftPosField.setAccessible(true);
            topPosField = AbstractContainerScreen.class.getDeclaredField("topPos");
            topPosField.setAccessible(true);
            LOG.info("Reflection fields resolved successfully");
        } catch (NoSuchFieldException e) {
            WynnSortMod.logError("Failed to resolve AbstractContainerScreen position fields", e);
        }
    }

    private static int getLeftPos(AbstractContainerScreen<?> screen) {
        if (!fieldsResolved) resolveFields();
        if (leftPosField == null) return Integer.MIN_VALUE;
        try {
            return leftPosField.getInt(screen);
        } catch (IllegalAccessException e) {
            LOG.warn("Reflection access failed for leftPos field", e);
            return Integer.MIN_VALUE;
        }
    }

    private static int getTopPos(AbstractContainerScreen<?> screen) {
        if (!fieldsResolved) resolveFields();
        if (topPosField == null) return Integer.MIN_VALUE;
        try {
            return topPosField.getInt(screen);
        } catch (IllegalAccessException e) {
            LOG.warn("Reflection access failed for topPos field", e);
            return Integer.MIN_VALUE;
        }
    }

}
