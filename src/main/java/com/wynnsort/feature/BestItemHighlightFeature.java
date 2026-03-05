package com.wynnsort.feature;

import com.wynnsort.SortState;
import com.wynnsort.StatFilter;
import com.wynnsort.config.WynnSortConfig;
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

import java.util.List;
import java.util.Optional;

/**
 * Draws a 1px gold border around the slot of the highest-scoring gear item
 * in the container, based on the current SortState mode.
 */
public class BestItemHighlightFeature {

    public static final BestItemHighlightFeature INSTANCE = new BestItemHighlightFeature();

    private static final int GOLD_BORDER_COLOR = 0xFFFFD700;

    // Per-frame cache for best slot computation
    private long lastFrameTime = -1;
    private Slot cachedBestSlot = null;

    private BestItemHighlightFeature() {}

    @SubscribeEvent
    public void onContainerRender(ContainerRenderEvent event) {
        if (!WynnSortConfig.INSTANCE.overlayEnabled) {
            return;
        }

        AbstractContainerScreen<?> screen = event.getScreen();
        GuiGraphics guiGraphics = event.getGuiGraphics();

        // Recompute best slot once per frame
        long currentFrame = net.minecraft.client.Minecraft.getInstance().getFrameTimeNs();
        if (currentFrame != lastFrameTime) {
            lastFrameTime = currentFrame;
            try {
                cachedBestSlot = findBestSlot(screen);
            } catch (Exception e) {
                cachedBestSlot = null;
                return;
            }
        }

        Slot bestSlot = cachedBestSlot;
        if (bestSlot == null) {
            return;
        }

        // Slot x/y are relative to the container's leftPos/topPos (widened via access widener)
        int containerLeft = screen.leftPos;
        int containerTop = screen.topPos;

        int x = bestSlot.x + containerLeft;
        int y = bestSlot.y + containerTop;
        drawBorder(guiGraphics, x, y, 16, 16, GOLD_BORDER_COLOR);
    }

    /**
     * Scans all slots and returns the one with the highest score, or null if none found.
     */
    private Slot findBestSlot(AbstractContainerScreen<?> screen) {
        List<StatFilter> filters = SortState.getFilters();
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

        return bestSlot;
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

}
