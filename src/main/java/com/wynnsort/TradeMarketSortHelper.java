package com.wynnsort;

import com.wynntils.screens.base.widgets.ItemSearchWidget;
import com.wynntils.screens.trademarket.TradeMarketSearchResultScreen;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Isolates Wynntils class references so they only load when the user
 * presses the sort keybind while a trade market screen is open.
 */
public final class TradeMarketSortHelper {

    private static final Pattern SORT_PATTERN = Pattern.compile("\\bsort:\\S+");

    private static Field itemSearchWidgetField;
    private static boolean fieldResolved = false;

    private TradeMarketSortHelper() {}

    public static void tryToggleSort(Screen screen) {
        if (!(screen instanceof TradeMarketSearchResultScreen tradeScreen)) {
            return;
        }

        if (!fieldResolved) {
            resolveField();
        }

        if (itemSearchWidgetField == null) {
            WynnSortMod.logWarn("itemSearchWidget field was not resolved; cannot toggle sort");
            return;
        }

        try {
            ItemSearchWidget searchWidget = (ItemSearchWidget) itemSearchWidgetField.get(tradeScreen);
            if (searchWidget == null) {
                return;
            }

            String currentText = searchWidget.getTextBoxInput();
            Matcher m = SORT_PATTERN.matcher(currentText);

            if (m.find()) {
                // Remove existing sort token
                String newText = m.replaceAll("").trim();
                searchWidget.setTextBoxInput(newText);
                WynnSortMod.log("Quality sort disabled");
            } else {
                // Add sort token based on current SortState
                String token = SortState.getSortToken();
                String newText = currentText.isEmpty() ? token : currentText + " " + token;
                searchWidget.setTextBoxInput(newText);
                WynnSortMod.log("Quality sort enabled: {}", token);
            }
        } catch (IllegalAccessException e) {
            WynnSortMod.logError("Failed to access itemSearchWidget", e);
        }
    }

    private static void resolveField() {
        fieldResolved = true;
        try {
            itemSearchWidgetField = TradeMarketSearchResultScreen.class.getDeclaredField("itemSearchWidget");
            itemSearchWidgetField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            WynnSortMod.logError("Failed to find itemSearchWidget field", e);
        }
    }
}
