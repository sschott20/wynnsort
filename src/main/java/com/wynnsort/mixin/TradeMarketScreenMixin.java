package com.wynnsort.mixin;

import com.wynnsort.SortState;
import com.wynnsort.WynnSortMod;
import com.wynnsort.config.WynnSortConfig;
import com.wynntils.screens.base.widgets.ItemSearchWidget;
import com.wynntils.screens.trademarket.TradeMarketSearchResultHolder;
import com.wynntils.screens.trademarket.TradeMarketSearchResultScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(value = TradeMarketSearchResultScreen.class)
public abstract class TradeMarketScreenMixin extends Screen {

    @Shadow(remap = false)
    private ItemSearchWidget itemSearchWidget;

    @Shadow(remap = false)
    private TradeMarketSearchResultHolder holder;

    @Unique
    private static final Pattern SORT_PATTERN = Pattern.compile("\\bsort:\\S+");

    /**
     * Tracks whether we've already applied the default "most recent" sort
     * for this trade market session. Reset when the trade market closes
     * (via {@link #wynnsort$resetDefaultSort()}).
     */
    @Unique
    private static boolean wynnsort$defaultSortApplied = false;

    @Unique
    private Button wynnsort$sortButton;

    @Unique
    private EditBox wynnsort$statInput;

    @Unique
    private boolean wynnsort$sortActive = false;

    protected TradeMarketScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "doInit", at = @At("RETURN"), remap = false)
    private void wynnsort$onDoInitReturn(CallbackInfo ci) {
        WynnSortMod.log("[WynnSort DEBUG] doInit mixin fired! Adding sort UI.");
        if (!WynnSortConfig.INSTANCE.sortButtonEnabled) {
            WynnSortMod.log("[WynnSort DEBUG] Sort button disabled in config, skipping.");
            return;
        }

        int renderX = (this.width - 176) / 2;
        int renderY = (this.height - 166) / 2;

        // Check if sort is already active in the search text
        String currentText = itemSearchWidget != null ? itemSearchWidget.getTextBoxInput() : "";
        wynnsort$sortActive = SORT_PATTERN.matcher(currentText).find();

        // Sort toggle button
        wynnsort$sortButton = Button.builder(
                        Component.literal(wynnsort$sortActive ? "Sorting" : "Sort"),
                        btn -> wynnsort$onSortButtonPress(btn))
                .pos(renderX + 178, renderY + 2)
                .size(40, 16)
                .build();
        this.addRenderableWidget(wynnsort$sortButton);

        // Stat name input field — type a stat name like "walkSpeed" or leave blank for overall
        wynnsort$statInput = new EditBox(
                this.font,
                renderX + 178, renderY + 20,
                72, 16,
                Component.literal("Stat"));
        wynnsort$statInput.setMaxLength(32);
        wynnsort$statInput.setHint(Component.literal("overall"));
        wynnsort$statInput.setValue(SortState.getTargetStat());
        wynnsort$statInput.setResponder(this::wynnsort$onStatInputChanged);
        this.addRenderableWidget(wynnsort$statInput);

        // Auto-set sort to "most recent" on the first search of this trade market session.
        // The Wynncraft trade market defaults to sorting by level. Clicking the sort button
        // once (left click = mouse button 0) cycles it to "most recent". Once set, the
        // server remembers the choice until the trade market is closed entirely.
        if (!wynnsort$defaultSortApplied && WynnSortConfig.INSTANCE.defaultSortMostRecent && holder != null) {
            try {
                holder.changeSortingMode(0); // left click cycles: Level -> Most Recent
                wynnsort$defaultSortApplied = true;
                WynnSortMod.log("[WS:Sort] Applied default sort: most recent");
            } catch (Exception e) {
                WynnSortMod.logWarn("[WS:Sort] Failed to apply default sort: {}", e.getMessage());
            }
        }
    }

    @Unique
    private void wynnsort$onStatInputChanged(String text) {
        SortState.setTargetStat(text);
        // If sort is currently active, update the search query live
        if (wynnsort$sortActive) {
            wynnsort$applySortToken();
        }
    }

    @Unique
    private void wynnsort$onSortButtonPress(Button button) {
        if (itemSearchWidget == null) {
            return;
        }

        wynnsort$sortActive = !wynnsort$sortActive;

        if (wynnsort$sortActive) {
            wynnsort$applySortToken();
            button.setMessage(Component.literal("Sorting"));
        } else {
            wynnsort$removeSortToken();
            button.setMessage(Component.literal("Sort"));
        }
    }

    @Unique
    private void wynnsort$applySortToken() {
        if (itemSearchWidget == null) return;
        String currentText = itemSearchWidget.getTextBoxInput();
        String token = SortState.getSortToken();

        // Remove any existing sort:xxx token first
        Matcher m = SORT_PATTERN.matcher(currentText);
        String cleaned = m.replaceAll("").trim();

        String newText = cleaned.isEmpty() ? token : cleaned + " " + token;
        itemSearchWidget.setTextBoxInput(newText);
    }

    @Unique
    private void wynnsort$removeSortToken() {
        if (itemSearchWidget == null) return;
        String currentText = itemSearchWidget.getTextBoxInput();
        Matcher m = SORT_PATTERN.matcher(currentText);
        String newText = m.replaceAll("").trim();
        itemSearchWidget.setTextBoxInput(newText);
    }

    /**
     * Resets the default sort flag so the next trade market session
     * will apply "most recent" sort again on first search.
     * Called by {@link com.wynnsort.feature.TradeMarketLogger} when the
     * trade market state transitions to NOT_ACTIVE.
     */
    @Unique
    public static void wynnsort$resetDefaultSort() {
        wynnsort$defaultSortApplied = false;
    }
}
