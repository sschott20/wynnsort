package com.wynnsort;

import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
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

    private static final FeatureLogger LOG = new FeatureLogger("Sort", DiagnosticLog.Category.TRADE_MARKET);
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
            LOG.warn("itemSearchWidget field was not resolved; cannot toggle sort");
            return;
        }

        try {
            ItemSearchWidget searchWidget = (ItemSearchWidget) itemSearchWidgetField.get(tradeScreen);
            if (searchWidget == null) {
                return;
            }

            LOG.info("Toggle sort on screenClass={}, widgetClass={}", tradeScreen.getClass().getName(), searchWidget.getClass().getName());

            String currentText = searchWidget.getTextBoxInput();
            Matcher m = SORT_PATTERN.matcher(currentText);

            if (m.find()) {
                String newText = m.replaceAll("").trim();
                searchWidget.setTextBoxInput(newText);
                LOG.info("Quality sort disabled");
            } else {
                String token = SortState.getSortToken();
                String newText = currentText.isEmpty() ? token : currentText + " " + token;
                searchWidget.setTextBoxInput(newText);
                LOG.info("Quality sort enabled: {}", token);
            }
        } catch (IllegalAccessException e) {
            LOG.error("Failed to access itemSearchWidget", e);
        }
    }

    /**
     * Injects a specific sort token into the Wynntils search widget,
     * replacing any existing sort:X token. Used by preset application.
     */
    public static void tryInjectSortToken(Screen screen, String sortToken) {
        if (!(screen instanceof TradeMarketSearchResultScreen tradeScreen)) {
            return;
        }

        if (!fieldResolved) {
            resolveField();
        }

        if (itemSearchWidgetField == null) {
            LOG.warn("itemSearchWidget field was not resolved; cannot inject sort token");
            return;
        }

        try {
            ItemSearchWidget searchWidget = (ItemSearchWidget) itemSearchWidgetField.get(tradeScreen);
            if (searchWidget == null) return;

            LOG.info("Inject sort token on screenClass={}, widgetClass={}, token='{}'", tradeScreen.getClass().getName(), searchWidget.getClass().getName(), sortToken);

            String currentText = searchWidget.getTextBoxInput();
            Matcher m = SORT_PATTERN.matcher(currentText);
            String cleaned = m.replaceAll("").trim();

            String newText = cleaned.isEmpty() ? sortToken : cleaned + " " + sortToken;
            searchWidget.setTextBoxInput(newText);
            LOG.info("Injected sort token from preset: {}", sortToken);
        } catch (IllegalAccessException e) {
            LOG.error("Failed to inject sort token", e);
        }
    }

    private static void resolveField() {
        fieldResolved = true;
        try {
            itemSearchWidgetField = TradeMarketSearchResultScreen.class.getDeclaredField("itemSearchWidget");
            itemSearchWidgetField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            LOG.error("Failed to find itemSearchWidget field", e);
        }
    }
}
