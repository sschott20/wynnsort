package com.wynnsort;

import com.wynnsort.config.WynnSortConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Automatically clicks the sort button in Wynncraft's trade market
 * to switch from "Most Recent" to "Cheapest First" when the screen opens.
 *
 * The sort button (slot 52, named "Sort Results") cycles through options.
 * Default is "Most Recent"; 3 clicks reaches "Cheapest First".
 * Only triggers once per trade market session (resets when leaving the trade market).
 */
public final class TradeMarketAutoSort {

    private static final int SORT_SLOT = 52;
    private static final int CLICKS_TO_CHEAPEST = 4;
    private static final int WAIT_FRAMES = 15;        // wait for items to load
    private static final int FRAMES_BETWEEN = 20;     // wait between clicks (~333ms at 60fps)

    private static boolean pending = false;
    private static boolean alreadySorted = false;     // prevents re-triggering on subsequent searches
    private static int frameCount = 0;
    private static int clickCount = 0;

    private TradeMarketAutoSort() {}

    /**
     * Called when a container screen opens.
     * Only schedules auto-sort if we haven't already sorted in this trade market session.
     */
    public static void onScreenOpen(String title) {
        if (!WynnSortConfig.INSTANCE.autoSortCheapest) return;

        String clean = stripFormatting(title);

        if (isWynncraftContainer(clean)) {
            if (alreadySorted) {
                // Already sorted in this session, skip
                return;
            }
            WynnSortMod.log("[WynnSort] Wynncraft container detected, will auto-sort");
            pending = true;
            frameCount = 0;
            clickCount = 0;
        }
    }

    /**
     * Called every render frame while a container screen is open.
     * Clicks the sort button the required number of times.
     */
    public static void tick(AbstractContainerScreen<?> screen) {
        if (!pending) return;
        frameCount++;
        if (frameCount < WAIT_FRAMES) return;

        // Check if the sort button slot exists and has an item
        if (SORT_SLOT >= screen.getMenu().slots.size()) {
            // Not a trade market container (not enough slots), abort
            pending = false;
            return;
        }

        Slot slot = screen.getMenu().getSlot(SORT_SLOT);
        ItemStack stack = slot.getItem();

        if (stack.isEmpty()) {
            // Items haven't loaded yet, keep waiting
            if (frameCount > WAIT_FRAMES + 120) {
                // Waited too long, give up
                pending = false;
            }
            return;
        }

        String name = stripFormatting(stack.getHoverName().getString()).toLowerCase();

        // Verify this is actually the sort button
        if (!name.contains("sort")) {
            // Not a trade market screen, abort
            pending = false;
            return;
        }

        // Click the required number of times
        if (clickCount < CLICKS_TO_CHEAPEST) {
            clickSlot(screen, SORT_SLOT);
            clickCount++;
            WynnSortMod.log("[WynnSort] Clicked sort button ({}/{})",
                    clickCount, CLICKS_TO_CHEAPEST);
            frameCount = WAIT_FRAMES - FRAMES_BETWEEN; // wait before next click
        } else {
            WynnSortMod.log("[WynnSort] Auto-sort complete (cheapest first)");
            pending = false;
            alreadySorted = true;
        }
    }

    /**
     * Called when leaving the trade market entirely (non-Wynncraft screen or no screen).
     * Resets the session flag so next trade market visit will auto-sort again.
     */
    public static void onLeaveTradeMarket() {
        alreadySorted = false;
        pending = false;
    }

    private static boolean isWynncraftContainer(String cleanTitle) {
        if (cleanTitle.isEmpty()) return false;
        String lower = cleanTitle.toLowerCase();
        if (lower.contains("wynncraft servers") || lower.contains("chest")
                || lower.contains("crafting") || lower.contains("inventory")) {
            return false;
        }
        for (int i = 0; i < cleanTitle.length(); i++) {
            int cp = cleanTitle.codePointAt(i);
            if (cp > 0xFF) return true;
            if (Character.isHighSurrogate(cleanTitle.charAt(i))) return true;
        }
        return false;
    }

    private static void clickSlot(AbstractContainerScreen<?> screen, int slotIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;

        mc.gameMode.handleInventoryMouseClick(
                screen.getMenu().containerId,
                slotIndex,
                0,
                ClickType.PICKUP,
                mc.player
        );
    }

    private static String stripFormatting(String text) {
        return text.replaceAll("§.", "");
    }
}
