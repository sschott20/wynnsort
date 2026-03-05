package com.wynnsort.screen;

import com.wynnsort.config.WynnSortConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Simple settings screen for a single HUD element.
 * Shows toggle buttons for each display line that can be turned on/off.
 */
public class HudElementSettingsScreen extends Screen {

    private final Screen parent;
    private final List<ToggleEntry> toggles;

    public record ToggleEntry(String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {}

    public HudElementSettingsScreen(Screen parent, String title, List<ToggleEntry> toggles) {
        super(Component.literal(title));
        this.parent = parent;
        this.toggles = toggles;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int btnW = 200;
        int btnH = 20;
        int y = 40;
        int spacing = 24;

        for (ToggleEntry entry : toggles) {
            boolean current = entry.getter().get();
            String text = entry.label() + ": " + (current ? "ON" : "OFF");
            addRenderableWidget(Button.builder(Component.literal(text), b -> {
                boolean newVal = !entry.getter().get();
                entry.setter().accept(newVal);
                rebuildWidgets();
            }).bounds(centerX - btnW / 2, y, btnW, btnH).build());
            y += spacing;
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            WynnSortConfig.save();
            minecraft.setScreen(parent);
        }).bounds(centerX - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);
        gg.drawCenteredString(this.font, this.title, width / 2, 15, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        WynnSortConfig.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
