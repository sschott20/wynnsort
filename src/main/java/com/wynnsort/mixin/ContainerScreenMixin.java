package com.wynnsort.mixin;

import com.wynnsort.SortState;
import com.wynnsort.StatPickerHelper;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.market.SearchPreset;
import com.wynnsort.market.SearchPresetStore;
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

    @Unique private static final com.wynnsort.util.FeatureLogger wynnsort$LOG = new com.wynnsort.util.FeatureLogger("Mixin", com.wynnsort.util.DiagnosticLog.Category.MIXIN);

    @Unique private EditBox wynnsort$statInput;
    @Unique private Button wynnsort$noriButton;
    @Unique private Button wynnsort$overallButton;

    // Dynamic stat filter boxes
    @Unique private final List<EditBox> wynnsort$statBoxes = new ArrayList<>();
    @Unique private final List<String> wynnsort$statNames = new ArrayList<>();
    @Unique private boolean wynnsort$statsBuilt = false;
    @Unique private String wynnsort$lastContainerTitle = null;
    @Unique private static final int WYNNSORT$MAX_STAT_ROWS = 12;

    // Preset buttons
    @Unique private static final int WYNNSORT$PRESET_COUNT = 5;
    @Unique private final List<Button> wynnsort$presetButtons = new ArrayList<>();
    @Unique private int wynnsort$activePresetIndex = -1;
    // Preset name editing
    @Unique private EditBox wynnsort$presetNameInput = null;
    @Unique private int wynnsort$editingPresetIndex = -1;
    @Unique private String wynnsort$lastSyncedFilter = "";

    protected ContainerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void wynnsort$onInit(CallbackInfo ci) {
        wynnsort$statBoxes.clear();
        wynnsort$statNames.clear();
        wynnsort$statsBuilt = false;
        wynnsort$lastContainerTitle = this.getTitle().getString();

        if (!WynnSortConfig.INSTANCE.overlayEnabled) {
            return;
        }

        int x = leftPos + imageWidth + 8;
        int y = topPos + 2;

        wynnsort$statInput = new EditBox(this.font, x, y, 120, 16, Component.literal("Stat"));
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
        wynnsort$updateStatInputColor();

        boolean useWeighted = WynnSortConfig.INSTANCE.useWeightedScale;
        wynnsort$noriButton = Button.builder(
                Component.literal(useWeighted ? "[Nori]" : "Nori"),
                btn -> wynnsort$setScaleMode(true))
                .pos(x, y + 20).size(58, 14).build();
        this.addRenderableWidget(wynnsort$noriButton);

        wynnsort$overallButton = Button.builder(
                Component.literal(useWeighted ? "Overall" : "[Overall]"),
                btn -> wynnsort$setScaleMode(false))
                .pos(x + 62, y + 20).size(58, 14).build();
        this.addRenderableWidget(wynnsort$overallButton);

        // Preset buttons
        wynnsort$presetButtons.clear();
        wynnsort$activePresetIndex = -1;
        wynnsort$presetNameInput = null;
        wynnsort$editingPresetIndex = -1;

        if (WynnSortConfig.INSTANCE.searchPresetsEnabled) {
            int presetY = y + 38;
            int btnWidth = 22;
            int gap = 2;
            for (int i = 0; i < WYNNSORT$PRESET_COUNT; i++) {
                final int idx = i;
                Button presetBtn = Button.builder(
                        Component.literal(String.valueOf(i + 1)),
                        btn -> wynnsort$onPresetClick(idx))
                        .pos(x + i * (btnWidth + gap), presetY)
                        .size(btnWidth, 14).build();
                wynnsort$presetButtons.add(presetBtn);
                this.addRenderableWidget(presetBtn);
            }
        }

        wynnsort$LOG.info("Screen init: overlay={}, presets={}, filter='{}', title='{}', screenClass={}", WynnSortConfig.INSTANCE.overlayEnabled, WynnSortConfig.INSTANCE.searchPresetsEnabled, SortState.getRawInput(), this.getTitle().getString(), this.getClass().getSimpleName());
    }

    @Unique
    private void wynnsort$onPresetClick(int index) {
        SearchPreset preset = SearchPresetStore.getPreset(index);
        if (preset == null) {
            wynnsort$saveCurrentAsPreset(index);
            return;
        }
        wynnsort$applyPreset(index, preset);
    }

    @Unique
    private void wynnsort$applyPreset(int index, SearchPreset preset) {
        if (preset == null) return;
        SortState.setRawInput(preset.query != null ? preset.query : "");
        if (wynnsort$statInput != null) {
            wynnsort$statInput.setResponder(t -> {});
            wynnsort$statInput.setValue(SortState.getRawInput());
            wynnsort$statInput.setResponder(text -> {
                SortState.setRawInput(text);
                WynnSortConfig.INSTANCE.lastFilter = text == null ? "" : text;
                WynnSortConfig.save();
                wynnsort$updateStatInputColor();
            });
        }
        WynnSortConfig.INSTANCE.lastFilter = SortState.getRawInput();
        WynnSortConfig.save();
        wynnsort$updateStatInputColor();

        if (preset.sortToken != null && !preset.sortToken.isEmpty()) {
            try {
                com.wynnsort.TradeMarketSortHelper.tryInjectSortToken(
                        (Screen) (Object) this, preset.sortToken);
            } catch (Exception e) {
                WynnSortMod.logWarn("[WynnSort] Failed to inject sort token for preset: {}", e.getMessage());
            }
        }
        wynnsort$activePresetIndex = index;
        wynnsort$LOG.info("Preset applied: idx={}, name='{}', query='{}'", index, preset.name, preset.query);
        if (this.minecraft != null && this.minecraft.player != null) {
            String name = preset.name != null ? preset.name : "Preset " + (index + 1);
            this.minecraft.player.displayClientMessage(
                    Component.literal("[WynnSort] Applied preset: " + name), true);
        }
    }

    @Unique
    private void wynnsort$saveCurrentAsPreset(int index) {
        // Clean up any existing preset name input first
        if (wynnsort$presetNameInput != null) {
            this.removeWidget(wynnsort$presetNameInput);
            wynnsort$presetNameInput = null;
        }
        wynnsort$editingPresetIndex = index;
        int x = leftPos + imageWidth + 8;
        int presetNameY = topPos + 58;
        wynnsort$presetNameInput = new EditBox(this.font, x, presetNameY, 120, 14, Component.literal("Preset Name"));
        wynnsort$presetNameInput.setMaxLength(24);
        wynnsort$presetNameInput.setHint(Component.literal("Preset " + (index + 1)));
        wynnsort$presetNameInput.setValue("Preset " + (index + 1));
        this.addRenderableWidget(wynnsort$presetNameInput);
        // Set Screen-level focus so charTyped dispatches to this widget exclusively
        this.setFocused(wynnsort$presetNameInput);
    }

    @Unique
    private void wynnsort$confirmPresetSave() {
        if (wynnsort$editingPresetIndex < 0 || wynnsort$presetNameInput == null) return;
        String name = wynnsort$presetNameInput.getValue().trim();
        if (name.isEmpty()) name = "Preset " + (wynnsort$editingPresetIndex + 1);
        SearchPreset preset = new SearchPreset(name, SortState.getRawInput(), SortState.getSortToken());
        SearchPresetStore.setPreset(wynnsort$editingPresetIndex, preset);
        wynnsort$LOG.info("Preset saved: idx={}, name='{}'", wynnsort$editingPresetIndex, name);
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("[WynnSort] Saved preset " + (wynnsort$editingPresetIndex + 1) + ": " + name), true);
        }
        this.removeWidget(wynnsort$presetNameInput);
        wynnsort$presetNameInput = null;
        wynnsort$editingPresetIndex = -1;
    }

    @Unique
    private void wynnsort$cancelPresetEdit() {
        if (wynnsort$presetNameInput != null) {
            this.removeWidget(wynnsort$presetNameInput);
            wynnsort$presetNameInput = null;
        }
        wynnsort$editingPresetIndex = -1;
    }

    @Unique
    private void wynnsort$cyclePresets() {
        wynnsort$LOG.info("Cycling presets from idx={}", wynnsort$activePresetIndex);
        if (!WynnSortConfig.INSTANCE.searchPresetsEnabled) return;
        if (SearchPresetStore.size() == 0) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(
                        Component.literal("[WynnSort] No presets saved"), true);
            }
            return;
        }
        int startIdx = wynnsort$activePresetIndex + 1;
        for (int attempt = 0; attempt < WYNNSORT$PRESET_COUNT; attempt++) {
            int idx = (startIdx + attempt) % WYNNSORT$PRESET_COUNT;
            SearchPreset preset = SearchPresetStore.getPreset(idx);
            if (preset != null) {
                wynnsort$applyPreset(idx, preset);
                return;
            }
        }
    }

    @Unique
    private void wynnsort$setScaleMode(boolean useWeighted) {
        wynnsort$LOG.info("Scale mode: weighted={}", useWeighted);
        WynnSortConfig.INSTANCE.useWeightedScale = useWeighted;
        WynnSortConfig.save();
        if (wynnsort$noriButton != null) wynnsort$noriButton.setMessage(Component.literal(useWeighted ? "[Nori]" : "Nori"));
        if (wynnsort$overallButton != null) wynnsort$overallButton.setMessage(Component.literal(useWeighted ? "Overall" : "[Overall]"));
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("[WynnSort] Scale: " + (useWeighted ? "Nori/Wynnpool weighted" : "Overall average")), true);
        }
    }

    @Unique
    private void wynnsort$updateStatInputColor() {
        if (wynnsort$statInput == null) return;
        String text = wynnsort$statInput.getValue();
        if (text == null || text.isEmpty() || text.contains(">") || text.contains("<") || text.contains(",")) {
            wynnsort$statInput.setTextColor(0xFFFFFFFF);
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
                        wynnsort$statInput.setTextColor(0xFFFFFFFF);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
        wynnsort$statInput.setTextColor(0xFFFF5555);
    }

    @Unique
    private void wynnsort$buildStatFilters() {
        String currentTitle = this.getTitle().getString();
        if (wynnsort$statsBuilt && wynnsort$lastContainerTitle != null
                && !wynnsort$lastContainerTitle.equals(currentTitle)) {
            for (EditBox box : wynnsort$statBoxes) this.removeWidget(box);
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
        int baseY = WynnSortConfig.INSTANCE.searchPresetsEnabled ? topPos + 76 : topPos + 40;
        int maxRows = Math.min(stats.size(), WYNNSORT$MAX_STAT_ROWS);
        for (int i = 0; i < maxRows; i++) {
            EditBox box = new EditBox(this.font, x + 90, baseY + i * 16, 30, 14, Component.literal(stats.get(i)));
            box.setMaxLength(3);
            box.setHint(Component.literal("%"));
            box.setResponder(text -> wynnsort$syncFiltersFromBoxes());
            wynnsort$statBoxes.add(box);
            this.addRenderableWidget(box);
        }
    }

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
        if (!filter.equals(wynnsort$lastSyncedFilter)) {
            wynnsort$lastSyncedFilter = filter;
            wynnsort$LOG.info("Stat filters synced: '{}'", filter);
        }
        SortState.setRawInput(filter);
        if (wynnsort$statInput != null) {
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

    @Unique
    private boolean wynnsort$isAnyInputFocused() {
        if (wynnsort$statInput != null && wynnsort$statInput.isFocused()) return true;
        if (wynnsort$presetNameInput != null && wynnsort$presetNameInput.isFocused()) return true;
        for (EditBox box : wynnsort$statBoxes) {
            if (box.isFocused()) return true;
        }
        return false;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wynnsort$onRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!WynnSortConfig.INSTANCE.overlayEnabled) return;
        wynnsort$buildStatFilters();
        if (wynnsort$statsBuilt) {
            int x = leftPos + imageWidth + 8;
            int baseY = WynnSortConfig.INSTANCE.searchPresetsEnabled ? topPos + 76 : topPos + 40;
            int maxRows = Math.min(wynnsort$statNames.size(), wynnsort$statBoxes.size());
            for (int i = 0; i < maxRows; i++) {
                String name = wynnsort$statNames.get(i);
                if (this.font.width(name) > 85) {
                    while (this.font.width(name + "..") > 85 && name.length() > 1) name = name.substring(0, name.length() - 1);
                    name = name + "..";
                }
                guiGraphics.drawString(this.font, name, x, baseY + i * 16 + 3, 0xFFCCCCCC);
            }
        }
        if (WynnSortConfig.INSTANCE.searchPresetsEnabled) {
            for (int i = 0; i < wynnsort$presetButtons.size(); i++) {
                Button btn = wynnsort$presetButtons.get(i);
                if (btn.isHoveredOrFocused() && mouseX >= btn.getX() && mouseX <= btn.getX() + btn.getWidth()
                        && mouseY >= btn.getY() && mouseY <= btn.getY() + btn.getHeight()) {
                    SearchPreset preset = SearchPresetStore.getPreset(i);
                    List<Component> tooltipLines = new ArrayList<>();
                    if (preset != null) {
                        tooltipLines.add(Component.literal(preset.name != null ? preset.name : "Preset " + (i + 1)));
                        if (preset.query != null && !preset.query.isEmpty())
                            tooltipLines.add(Component.literal("Query: " + preset.query).withStyle(s -> s.withColor(0xAAAAAA)));
                        tooltipLines.add(Component.literal("Click to apply").withStyle(s -> s.withColor(0x55FF55)));
                        tooltipLines.add(Component.literal("Right-click to overwrite").withStyle(s -> s.withColor(0xFFAA00)));
                    } else {
                        tooltipLines.add(Component.literal("Empty Preset " + (i + 1)));
                        tooltipLines.add(Component.literal("Click to save current filter").withStyle(s -> s.withColor(0x55FF55)));
                    }
                    guiGraphics.renderTooltip(this.font, tooltipLines, Optional.empty(), mouseX, mouseY);
                }
            }
            if (wynnsort$presetNameInput != null && wynnsort$editingPresetIndex >= 0) {
                guiGraphics.drawString(this.font, "Name:", leftPos + imageWidth + 8 - 30, topPos + 61, 0xFFCCCCCC);
            }
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void wynnsort$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 1 && WynnSortConfig.INSTANCE.searchPresetsEnabled) {
            for (int i = 0; i < wynnsort$presetButtons.size(); i++) {
                Button presetBtn = wynnsort$presetButtons.get(i);
                if (mouseX >= presetBtn.getX() && mouseX <= presetBtn.getX() + presetBtn.getWidth()
                        && mouseY >= presetBtn.getY() && mouseY <= presetBtn.getY() + presetBtn.getHeight()) {
                    wynnsort$saveCurrentAsPreset(i);
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wynnsort$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (wynnsort$presetNameInput != null && wynnsort$presetNameInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                wynnsort$confirmPresetSave();
                cir.setReturnValue(true);
                return;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                wynnsort$cancelPresetEdit();
                cir.setReturnValue(true);
                return;
            }
            wynnsort$presetNameInput.keyPressed(keyCode, scanCode, modifiers);
            cir.setReturnValue(true);
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_J && !wynnsort$isAnyInputFocused()) {
            WynnSortConfig.INSTANCE.overlayEnabled = !WynnSortConfig.INSTANCE.overlayEnabled;
            WynnSortConfig.save();
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(
                        Component.literal("[WynnSort] Overlay: " + (WynnSortConfig.INSTANCE.overlayEnabled ? "ON" : "OFF")), true);
            }
            if (this.minecraft != null) this.resize(this.minecraft, this.width, this.height);
            wynnsort$LOG.info("Key: J -> overlay toggled");
            cir.setReturnValue(true);
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_P && !wynnsort$isAnyInputFocused()) {
            wynnsort$LOG.info("Key: P -> cycle presets");
            wynnsort$cyclePresets();
            cir.setReturnValue(true);
            return;
        }
        if (wynnsort$isAnyInputFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) return;
            if (wynnsort$statInput != null && wynnsort$statInput.isFocused()) wynnsort$statInput.keyPressed(keyCode, scanCode, modifiers);
            for (EditBox box : wynnsort$statBoxes) {
                if (box.isFocused()) { box.keyPressed(keyCode, scanCode, modifiers); break; }
            }
            cir.setReturnValue(true);
        }
    }
}
