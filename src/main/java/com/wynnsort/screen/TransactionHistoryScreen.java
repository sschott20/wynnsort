package com.wynnsort.screen;

import com.wynnsort.config.WynnSortConfig;
import com.wynnsort.history.TransactionPair;
import com.wynnsort.history.TransactionRecord;
import com.wynnsort.history.TransactionStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TransactionHistoryScreen extends Screen {

    private enum SortField { DATE, NAME, PRICE }
    private enum TypeFilter { ALL, BUY, SELL }

    private TransactionListWidget transactionList;
    private SortField currentSort = SortField.DATE;
    private boolean ascending = false;
    private TypeFilter typeFilter = TypeFilter.ALL;
    private boolean grouped = false;

    private EditBox nameSearchBox;
    private EditBox minPriceBox;
    private String nameSearch = "";
    private long minPrice;

    // Cached stats
    private long totalSpent = 0;
    private long totalEarned = 0;
    private long netProfit = 0;
    private int displayCount = 0;

    public TransactionHistoryScreen() {
        super(Component.literal("Trade Market History"));
        minPrice = WynnSortConfig.INSTANCE.tradeHistoryMinPriceFilter;
    }

    @Override
    protected void init() {
        int listTop = 60;
        int listHeight = this.height - listTop - 26;

        transactionList = new TransactionListWidget(minecraft, this.width, listHeight, listTop, 20,
                this::onDeleteEntry);
        addRenderableWidget(transactionList);

        // Row 1: Sort buttons + Type filter
        int btnY = 4;
        int btnW = 60;
        int btnH = 16;
        int x = 4;

        addRenderableWidget(Button.builder(Component.literal(sortLabel(SortField.DATE)), btn -> {
            toggleSort(SortField.DATE);
            refreshButtons();
        }).bounds(x, btnY, btnW, btnH).build());

        addRenderableWidget(Button.builder(Component.literal(sortLabel(SortField.NAME)), btn -> {
            toggleSort(SortField.NAME);
            refreshButtons();
        }).bounds(x + btnW + 3, btnY, btnW, btnH).build());

        addRenderableWidget(Button.builder(Component.literal(sortLabel(SortField.PRICE)), btn -> {
            toggleSort(SortField.PRICE);
            refreshButtons();
        }).bounds(x + (btnW + 3) * 2, btnY, btnW, btnH).build());

        // Type filter buttons
        int typeX = x + (btnW + 3) * 3 + 10;
        int typeBtnW = 40;
        addRenderableWidget(Button.builder(
                Component.literal(typeFilter == TypeFilter.ALL ? "[All]" : "All"), btn -> {
            typeFilter = TypeFilter.ALL;
            refreshList();
            refreshButtons();
        }).bounds(typeX, btnY, typeBtnW, btnH).build());

        addRenderableWidget(Button.builder(
                Component.literal(typeFilter == TypeFilter.BUY ? "[Buy]" : "Buy"), btn -> {
            typeFilter = TypeFilter.BUY;
            refreshList();
            refreshButtons();
        }).bounds(typeX + typeBtnW + 3, btnY, typeBtnW, btnH).build());

        addRenderableWidget(Button.builder(
                Component.literal(typeFilter == TypeFilter.SELL ? "[Sell]" : "Sell"), btn -> {
            typeFilter = TypeFilter.SELL;
            refreshList();
            refreshButtons();
        }).bounds(typeX + (typeBtnW + 3) * 2, btnY, typeBtnW, btnH).build());

        // Group toggle button
        int groupX = typeX + (typeBtnW + 3) * 3 + 6;
        int groupW = 50;
        addRenderableWidget(Button.builder(
                Component.literal(grouped ? "[Group]" : "Group"), btn -> {
            grouped = !grouped;
            refreshList();
            refreshButtons();
        }).bounds(groupX, btnY, groupW, btnH).build());

        // Clear All button (right side)
        int clearW = 60;
        addRenderableWidget(Button.builder(Component.literal("Clear All"), btn -> {
            TransactionStore.clearTransactions();
            refreshList();
        }).bounds(this.width - clearW - 4, btnY, clearW, btnH).build());

        // Row 2: Search + Price filter
        int row2Y = btnY + btnH + 4;
        int boxH = 16;

        // Name search
        nameSearchBox = new EditBox(this.font, 4, row2Y, 140, boxH, Component.literal("Search"));
        nameSearchBox.setHint(Component.literal("Search item name..."));
        nameSearchBox.setMaxLength(50);
        nameSearchBox.setValue(nameSearch);
        nameSearchBox.setResponder(val -> {
            nameSearch = val;
            refreshList();
        });
        addRenderableWidget(nameSearchBox);

        // Min price filter
        minPriceBox = new EditBox(this.font, 180, row2Y, 100, boxH, Component.literal("Min Price"));
        minPriceBox.setHint(Component.literal("Min price..."));
        minPriceBox.setMaxLength(12);
        minPriceBox.setValue(minPrice > 0 ? String.valueOf(minPrice) : "");
        minPriceBox.setResponder(val -> {
            try {
                minPrice = val.isEmpty() ? 0 : Long.parseLong(val.replace(",", ""));
            } catch (NumberFormatException e) {
                minPrice = 0;
            }
            refreshList();
        });
        addRenderableWidget(minPriceBox);

        refreshList();
    }

    private void onDeleteEntry(TransactionRecord record) {
        TransactionStore.removeTransaction(record);
        refreshList();
    }

    private void toggleSort(SortField field) {
        if (currentSort == field) {
            ascending = !ascending;
        } else {
            currentSort = field;
            ascending = field == SortField.NAME;
        }
        refreshList();
    }

    private String sortLabel(SortField field) {
        String name = switch (field) {
            case DATE -> "Date";
            case NAME -> "Name";
            case PRICE -> "Price";
        };
        if (currentSort == field) {
            name += ascending ? " \u25B2" : " \u25BC";
        }
        return name;
    }

    private void refreshButtons() {
        rebuildWidgets();
    }

    private void refreshList() {
        if (transactionList == null) return;

        List<TransactionRecord> records = new ArrayList<>(TransactionStore.getTransactions());
        int taxPercent = WynnSortConfig.INSTANCE.tradeMarketBuyTaxPercent;

        // Apply filters
        records.removeIf(r -> {
            // Type filter
            if (typeFilter == TypeFilter.BUY && r.type != TransactionRecord.Type.BUY) return true;
            if (typeFilter == TypeFilter.SELL && r.type != TransactionRecord.Type.SELL) return true;

            // Price filter — never hide items with unknown price (0 = unknown, not free)
            if (minPrice > 0 && r.priceEmeralds > 0) {
                long displayPrice = r.priceEmeralds;
                if (r.type == TransactionRecord.Type.BUY && taxPercent > 0) {
                    displayPrice = displayPrice + (displayPrice * taxPercent / 100);
                }
                if (displayPrice < minPrice) return true;
            }

            // Name filter
            if (!nameSearch.isEmpty() &&
                    (r.itemName == null || !r.itemName.toLowerCase().contains(nameSearch.toLowerCase()))) return true;

            return false;
        });

        // Sort
        Comparator<TransactionRecord> cmp = switch (currentSort) {
            case DATE -> Comparator.comparingLong(r -> r.timestamp);
            case NAME -> Comparator.comparing(r -> r.itemName != null ? r.itemName : "", String.CASE_INSENSITIVE_ORDER);
            case PRICE -> Comparator.comparingLong(r -> r.priceEmeralds);
        };

        if (!ascending) {
            cmp = cmp.reversed();
        }

        records.sort(cmp);

        // Calculate stats
        totalSpent = 0;
        totalEarned = 0;
        displayCount = records.size();
        for (TransactionRecord r : records) {
            if (r.type == TransactionRecord.Type.BUY) {
                long cost = r.priceEmeralds;
                if (taxPercent > 0) cost = cost + (cost * taxPercent / 100);
                totalSpent += cost;
            } else {
                totalEarned += r.priceEmeralds;
            }
        }

        if (grouped) {
            List<TransactionRecord> unpairedSells = new ArrayList<>();
            List<TransactionPair> pairs = TransactionStore.pairTransactions(records, taxPercent, unpairedSells);
            netProfit = 0;
            for (TransactionPair p : pairs) {
                if (p.sell != null) netProfit += p.profitEmeralds;
            }
            displayCount = pairs.size() + unpairedSells.size();
            transactionList.setGroupedTransactions(pairs, unpairedSells, taxPercent);
        } else {
            netProfit = 0;
            transactionList.setTransactions(records, taxPercent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Bottom summary bar
        int bottomY = this.height - 14;
        String summary = String.format("%d entries | Spent: %s | Earned: %s",
                displayCount,
                TransactionListWidget.Entry.formatPrice(totalSpent),
                TransactionListWidget.Entry.formatPrice(totalEarned));
        if (grouped && netProfit != 0) {
            String sign = netProfit > 0 ? "+" : "-";
            summary += " | Net: " + sign + TransactionListWidget.Entry.formatPrice(Math.abs(netProfit));
        }
        int summaryColor = 0xFFAAAAAA;
        if (grouped && netProfit > 0) summaryColor = 0xFF55FF55;
        else if (grouped && netProfit < 0) summaryColor = 0xFFFF5555;
        guiGraphics.drawString(this.font, summary, 6, bottomY, summaryColor);

        // Tax info (right side)
        int taxPercent = WynnSortConfig.INSTANCE.tradeMarketBuyTaxPercent;
        if (taxPercent > 0) {
            String taxInfo = "Buy tax: " + taxPercent + "%";
            int taxWidth = this.font.width(taxInfo);
            guiGraphics.drawString(this.font, taxInfo, this.width - taxWidth - 6, bottomY, 0xFF888888);
        }

        // Min price label
        guiGraphics.drawString(this.font, "Min:", 164, 28, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
