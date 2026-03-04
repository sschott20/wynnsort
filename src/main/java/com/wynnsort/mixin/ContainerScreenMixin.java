package com.wynnsort.mixin;

import com.wynnsort.SortState;
import com.wynnsort.StatPickerHelper;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.util.ScoreComputation;
import com.wynntils.core.components.Models;
import com.wynntils.models.gear.type.GearInstance;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.stats.type.StatActualValue;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin extends Screen {

    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;

    @Unique private EditBox wynnsort$statInput;
    @Unique private Button wynnsort$noriButton;
    @Unique private Button wynnsort$overallButton;

    // Dynamic stat filter boxes
    @Unique private final List<EditBox> wynnsort$statBoxes = new ArrayList<>();
    @Unique private final List<String> wynnsort$statNames = new ArrayList<>();
    @Unique private boolean wynnsort$statsBuilt = false;
    @Unique private String wynnsort$lastContainerTitle = null;
    @Unique private static final int WYNNSORT$MAX_STAT_ROWS = 12;

    protected ContainerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void wynnsort$onInit(CallbackInfo ci) {
        // Reset stat filter state for new screen
        wynnsort$statBoxes.clear();
        wynnsort$statNames.clear();
        wynnsort$statsBuilt = false;
        wynnsort$lastContainerTitle = this.getTitle().getString();

        if (!WynnSortConfig.INSTANCE.overlayEnabled) {
            return;
        }

        int x = leftPos + imageWidth + 8;
        int y = topPos + 2;

        // Search bar (still available for advanced text input)
        wynnsort$statInput = new EditBox(
                this.font,
                x, y,
                120, 16,
                Component.literal("Stat"));
        wynnsort$statInput.setMaxLength(128);
        wynnsort$statInput.setHint(Component.literal("overall"));

        if (SortState.getRawInput().isEmpty()
                && WynnSortConfig.INSTANCE.lastFilter != null
                && !WynnSortConfig.INSTANCE.lastFilter.isEmpty()) {
            SortState.setRawInput(WynnSortConfig.INSTANCE.lastFilter);
        }
        wynnsort$statInput.setValue(SortState.getRawInput());

        wynnsort$statInput.setResponder(text -> {
            SortState.setRawInput(text);
            WynnSortConfig.INSTANCE.lastFilter = text == null ? "" : text;
            WynnSortConfig.save();
            wynnsort$updateStatInputColor();
        });
        this.addRenderableWidget(wynnsort$statInput);

        // Color the restored filter
        wynnsort$updateStatInputColor();

        // Scale mode buttons
        boolean useWeighted = WynnSortConfig.INSTANCE.useWeightedScale;

        wynnsort$noriButton = Button.builder(
                        Component.literal(useWeighted ? "[Nori]" : "Nori"),
                        btn -> wynnsort$setScaleMode(true))
                .pos(x, y + 20)
                .size(58, 14)
                .build();
        this.addRenderableWidget(wynnsort$noriButton);

        wynnsort$overallButton = Button.builder(
                        Component.literal(useWeighted ? "Overall" : "[Overall]"),
                        btn -> wynnsort$setScaleMode(false))
                .pos(x + 62, y + 20)
                .size(58, 14)
                .build();
        this.addRenderableWidget(wynnsort$overallButton);
    }

    @Unique
    private void wynnsort$setScaleMode(boolean useWeighted) {
        WynnSortConfig.INSTANCE.useWeightedScale = useWeighted;
        WynnSortConfig.save();

        if (wynnsort$noriButton != null) {
            wynnsort$noriButton.setMessage(
                    Component.literal(useWeighted ? "[Nori]" : "Nori"));
        }
        if (wynnsort$overallButton != null) {
            wynnsort$overallButton.setMessage(
                    Component.literal(useWeighted ? "Overall" : "[Overall]"));
        }

        if (this.minecraft != null && this.minecraft.player != null) {
            String mode = useWeighted ? "Nori/Wynnpool weighted" : "Overall average";
            this.minecraft.player.displayClientMessage(
                    Component.literal("[WynnSort] Scale: " + mode), true);
        }
    }

    @Unique
    private void wynnsort$updateStatInputColor() {
        if (wynnsort$statInput == null) return;
        String text = wynnsort$statInput.getValue();
        if (text == null || text.isEmpty() || text.contains(">") || text.contains("<") || text.contains(",")) {
            wynnsort$statInput.setTextColor(0xFFFFFFFF); // white for empty, filter-mode, or multi-stat
            return;
        }
        String target = text.toLowerCase().trim();
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            try {
                Optional<WynnItem> opt = Models.Item.getWynnItem(stack);
                if (opt.isEmpty()) continue;
                if (!(opt.get() instanceof GearItem gearItem)) continue;
                Optional<GearInstance> instOpt = gearItem.getItemInstance();
                if (instOpt.isEmpty()) continue;
                for (StatActualValue actual : instOpt.get().identifications()) {
                    if (ScoreComputation.statMatches(actual, target)) {
                        wynnsort$statInput.setTextColor(0xFFFFFFFF); // valid — white
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
        wynnsort$statInput.setTextColor(0xFFFF5555); // no match — red
    }

    /**
     * Lazily builds stat filter boxes once container items are loaded.
     */
    @Unique
    private void wynnsort$buildStatFilters() {
        // Detect container switch (title changed) and reset stat boxes
        String currentTitle = this.getTitle().getString();
        if (wynnsort$statsBuilt && wynnsort$lastContainerTitle != null
                && !wynnsort$lastContainerTitle.equals(currentTitle)) {
            for (EditBox box : wynnsort$statBoxes) {
                this.removeWidget(box);
            }
            wynnsort$statBoxes.clear();
            wynnsort$statNames.clear();
            wynnsort$statsBuilt = false;
            wynnsort$lastContainerTitle = currentTitle;
        }

        if (wynnsort$statsBuilt) return;
        if (!WynnSortConfig.INSTANCE.overlayEnabled) return;

        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        List<String> stats = StatPickerHelper.getAvailableStats(screen);
        if (stats.isEmpty()) return;

        wynnsort$statsBuilt = true;
        wynnsort$statNames.addAll(stats);

        int x = leftPos + imageWidth + 8;
        int baseY = topPos + 40; // below search bar + scale buttons
        int maxRows = Math.min(stats.size(), WYNNSORT$MAX_STAT_ROWS);

        for (int i = 0; i < maxRows; i++) {
            EditBox box = new EditBox(
                    this.font,
                    x + 90, baseY + i * 16,
                    30, 14,
                    Component.literal(stats.get(i)));
            box.setMaxLength(3);
            box.setHint(Component.literal("%"));
            box.setResponder(text -> wynnsort$syncFiltersFromBoxes());
            wynnsort$statBoxes.add(box);
            this.addRenderableWidget(box);
        }
    }

    /**
     * Constructs a filter string from all stat boxes and updates the filter state.
     */
    @Unique
    private void wynnsort$syncFiltersFromBoxes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wynnsort$statBoxes.size(); i++) {
            String val = wynnsort$statBoxes.get(i).getValue().trim();
            if (!val.isEmpty()) {
                try {
                    Float.parseFloat(val);
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(wynnsort$statNames.get(i)).append(" > ").append(val);
                } catch (NumberFormatException ignored) {}
            }
        }
        String filter = sb.toString();
        SortState.setRawInput(filter);
        if (wynnsort$statInput != null) {
            // Temporarily remove responder to avoid feedback loop
            wynnsort$statInput.setResponder(t -> {});
            wynnsort$statInput.setValue(filter);
            wynnsort$statInput.setResponder(text -> {
                SortState.setRawInput(text);
                WynnSortConfig.INSTANCE.lastFilter = text == null ? "" : text;
                WynnSortConfig.save();
                wynnsort$updateStatInputColor();
            });
        }
        WynnSortConfig.INSTANCE.lastFilter = filter;
        WynnSortConfig.save();
    }

    /**
     * Checks if any stat filter box is focused.
     */
    @Unique
    private boolean wynnsort$isAnyInputFocused() {
        if (wynnsort$statInput != null && wynnsort$statInput.isFocused()) return true;
        for (EditBox box : wynnsort$statBoxes) {
            if (box.isFocused()) return true;
        }
        return false;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wynnsort$onRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!WynnSortConfig.INSTANCE.overlayEnabled) return;

        // Lazily build stat filter boxes once items are loaded
        wynnsort$buildStatFilters();

        // Draw stat name labels next to each filter box
        if (wynnsort$statsBuilt) {
            int x = leftPos + imageWidth + 8;
            int baseY = topPos + 40;
            int maxRows = Math.min(wynnsort$statNames.size(), wynnsort$statBoxes.size());

            for (int i = 0; i < maxRows; i++) {
                String name = wynnsort$statNames.get(i);
                // Truncate long names to fit
                if (this.font.width(name) > 85) {
                    while (this.font.width(name + "..") > 85 && name.length() > 1) {
                        name = name.substring(0, name.length() - 1);
                    }
                    name = name + "..";
                }
                guiGraphics.drawString(this.font, name, x, baseY + i * 16 + 3, 0xFFCCCCCC);
            }
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wynnsort$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        // J key toggles overlay (keybinds don't fire while screens are open)
        if (keyCode == GLFW.GLFW_KEY_J && !wynnsort$isAnyInputFocused()) {
            WynnSortConfig.INSTANCE.overlayEnabled = !WynnSortConfig.INSTANCE.overlayEnabled;
            WynnSortConfig.save();

            if (this.minecraft != null && this.minecraft.player != null) {
                String state = WynnSortConfig.INSTANCE.overlayEnabled ? "ON" : "OFF";
                this.minecraft.player.displayClientMessage(
                        Component.literal("[WynnSort] Overlay: " + state), true);
            }

            // Re-init so widgets appear/disappear
            if (this.minecraft != null) {
                this.resize(this.minecraft, this.width, this.height);
            }

            cir.setReturnValue(true);
            return;
        }

        // When any input is focused, capture keys (except Escape)
        if (wynnsort$isAnyInputFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                return;
            }
            // Forward to the focused widget
            if (wynnsort$statInput != null && wynnsort$statInput.isFocused()) {
                wynnsort$statInput.keyPressed(keyCode, scanCode, modifiers);
            }
            for (EditBox box : wynnsort$statBoxes) {
                if (box.isFocused()) {
                    box.keyPressed(keyCode, scanCode, modifiers);
                    break;
                }
            }
            cir.setReturnValue(true);
        }
    }
}
