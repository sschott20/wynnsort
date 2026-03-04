package com.wynnsort.screen;

import com.wynnsort.history.TransactionPair;
import com.wynnsort.history.TransactionRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

public class TransactionListWidget extends AbstractSelectionList<TransactionListWidget.Entry> {

    private static final int COLOR_BUY = 0xFF55FF55;
    private static final int COLOR_SELL = 0xFFFF5555;
    private static final int COLOR_PRICE = 0xFFFFAA00;
    private static final int COLOR_DATE = 0xFFAAAAAA;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_DELETE = 0xFFFF4444;
    private static final int COLOR_DELETE_HOVER = 0xFFFF8888;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Consumer<TransactionRecord> onDelete;

    public TransactionListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight,
                                 Consumer<TransactionRecord> onDelete) {
        super(minecraft, width, height, y, itemHeight);
        this.onDelete = onDelete;
    }

    public void setTransactions(List<TransactionRecord> records, int buyTaxPercent) {
        clearEntries();
        for (TransactionRecord record : records) {
            addEntry(new Entry(record, buyTaxPercent, onDelete));
        }
    }

    public void setGroupedTransactions(List<TransactionPair> pairs, List<TransactionRecord> unpairedSells,
                                       int buyTaxPercent) {
        clearEntries();
        for (TransactionPair pair : pairs) {
            addEntry(new PairEntry(pair));
        }
        for (TransactionRecord sell : unpairedSells) {
            addEntry(new Entry(sell, buyTaxPercent, onDelete));
        }
    }

    @Override
    public int getRowWidth() {
        return this.width - 40;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }


    public static class Entry extends AbstractSelectionList.Entry<Entry> {
        private final TransactionRecord record;
        private final String dateStr;
        private final String priceStr;
        private final Consumer<TransactionRecord> onDelete;

        /** Protected no-arg constructor for subclasses that handle their own rendering. */
        protected Entry() {
            this.record = null;
            this.onDelete = null;
            this.dateStr = null;
            this.priceStr = null;
        }

        public Entry(TransactionRecord record, int buyTaxPercent, Consumer<TransactionRecord> onDelete) {
            this.record = record;
            this.onDelete = onDelete;
            this.dateStr = DATE_FORMAT.format(Instant.ofEpochMilli(record.timestamp));

            // Apply tax to buy prices
            long displayPrice = record.priceEmeralds;
            if (record.type == TransactionRecord.Type.BUY && buyTaxPercent > 0 && displayPrice > 0) {
                displayPrice = displayPrice + (displayPrice * buyTaxPercent / 100);
            }
            this.priceStr = formatPrice(displayPrice);
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width,
                           int height, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int textY = top + (height - 9) / 2;

            // Delete [X] button (left side)
            int delX = left + 2;
            int delW = mc.font.width("X") + 4;
            boolean hoveringDelete = isMouseOver && mouseX >= delX && mouseX <= delX + delW
                    && mouseY >= top && mouseY <= top + height;
            guiGraphics.drawString(mc.font, "X", delX + 2, textY,
                    hoveringDelete ? COLOR_DELETE_HOVER : COLOR_DELETE);

            // Date column
            guiGraphics.drawString(mc.font, dateStr, left + 20, textY, COLOR_DATE);

            // Type column
            String typeStr = record.type == TransactionRecord.Type.BUY ? "BUY" : "SELL";
            int typeColor = record.type == TransactionRecord.Type.BUY ? COLOR_BUY : COLOR_SELL;
            guiGraphics.drawString(mc.font, typeStr, left + 88, textY, typeColor);

            // Item name
            String itemStr = record.quantity > 1
                    ? record.quantity + "x " + record.itemName
                    : record.itemName;
            int maxNameWidth = width - 240;
            if (mc.font.width(itemStr) > maxNameWidth) {
                itemStr = mc.font.plainSubstrByWidth(itemStr, maxNameWidth - mc.font.width("...")) + "...";
            }
            guiGraphics.drawString(mc.font, itemStr, left + 128, textY, COLOR_NAME);

            // Price column (right-aligned)
            int priceWidth = mc.font.width(priceStr);
            guiGraphics.drawString(mc.font, priceStr, left + width - priceWidth - 4, textY, COLOR_PRICE);

            // Hover highlight
            if (isMouseOver && !hoveringDelete) {
                guiGraphics.fill(left, top, left + width, top + height, 0x20FFFFFF);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                Minecraft mc = Minecraft.getInstance();
                // Check if clicking the delete X button
                // The X is at approximately left + 2, width about 12px
                // We can't easily get "left" here, so check relative to the list
                if (onDelete != null) {
                    // The X button is always at the leftmost position of the entry
                    // Use a simple heuristic: if click is in the first 20px of the row
                    int listLeft = mc.getWindow().getGuiScaledWidth() / 2 - (mc.getWindow().getGuiScaledWidth() - 40) / 2;
                    if (mouseX < listLeft + 20) {
                        onDelete.accept(record);
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        public static String formatPrice(long emeralds) {
            if (emeralds <= 0) return "?";
            String raw = String.format("%,d", emeralds);
            if (emeralds >= 262144) {
                double stx = emeralds / 262144.0;
                String stxStr = formatDecimal(stx, 2);
                return raw + " (" + stxStr + "stx)";
            }
            return raw;
        }

        private static String formatDecimal(double value, int decimals) {
            if (value == (long) value) return String.valueOf((long) value);
            String s = String.format("%." + decimals + "f", value);
            if (s.contains(".")) {
                s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return s;
        }
    }

    public static class PairEntry extends Entry {
        private static final int COLOR_PROFIT = 0xFF55FF55;
        private static final int COLOR_LOSS = 0xFFFF5555;
        private static final int COLOR_PENDING = 0xFF888888;
        private static final int COLOR_ARROW = 0xFFAAAAAA;
        private static final int BAR_WIDTH = 2;

        private final TransactionPair pair;
        private final String dateStr;
        private final String itemName;
        private final String buyPriceStr;
        private final String sellPriceStr;
        private final String profitStr;
        private final int profitColor;
        private final int barColor;

        public PairEntry(TransactionPair pair) {
            this.pair = pair;
            long displayTs = pair.sell != null ? pair.sell.timestamp : pair.buy.timestamp;
            this.dateStr = DATE_FORMAT.format(Instant.ofEpochMilli(displayTs));

            this.itemName = pair.buy.quantity > 1
                    ? pair.buy.quantity + "x " + pair.buy.itemName
                    : pair.buy.itemName;

            this.buyPriceStr = Entry.formatPrice(pair.buy.priceEmeralds);

            if (pair.sell != null) {
                this.sellPriceStr = Entry.formatPrice(pair.sell.priceEmeralds);
                long profit = pair.profitEmeralds;
                if (profit >= 0) {
                    this.profitStr = "+" + Entry.formatPrice(profit);
                    this.profitColor = COLOR_PROFIT;
                    this.barColor = COLOR_PROFIT;
                } else {
                    this.profitStr = "-" + Entry.formatPrice(-profit);
                    this.profitColor = COLOR_LOSS;
                    this.barColor = COLOR_LOSS;
                }
            } else {
                this.sellPriceStr = null;
                this.profitStr = "pending";
                this.profitColor = COLOR_PENDING;
                this.barColor = COLOR_PENDING;
            }
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width,
                           int height, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            int textY = top + (height - 9) / 2;

            // Left-edge color bar
            guiGraphics.fill(left, top, left + BAR_WIDTH, top + height, barColor);

            // Date column
            guiGraphics.drawString(mc.font, dateStr, left + 6, textY, COLOR_DATE);

            // Item name
            int nameX = left + 74;
            int maxNameWidth = width - 280;
            String displayName = itemName;
            if (mc.font.width(displayName) > maxNameWidth) {
                displayName = mc.font.plainSubstrByWidth(displayName, maxNameWidth - mc.font.width("...")) + "...";
            }
            guiGraphics.drawString(mc.font, displayName, nameX, textY, COLOR_NAME);

            // Price section: "buy → sell" or "buy → ?"
            int priceX = left + width - 200;
            // Trim buy price to short form for the pair view
            String shortBuy = shortPrice(pair.buy.priceEmeralds);
            guiGraphics.drawString(mc.font, shortBuy, priceX, textY, COLOR_BUY);
            int arrowX = priceX + mc.font.width(shortBuy) + 2;
            guiGraphics.drawString(mc.font, "\u2192", arrowX, textY, COLOR_ARROW);
            int sellX = arrowX + mc.font.width("\u2192") + 2;
            if (sellPriceStr != null) {
                String shortSell = shortPrice(pair.sell.priceEmeralds);
                guiGraphics.drawString(mc.font, shortSell, sellX, textY, COLOR_SELL);
            } else {
                guiGraphics.drawString(mc.font, "?", sellX, textY, COLOR_PENDING);
            }

            // Profit/loss (right-aligned)
            int profitWidth = mc.font.width(profitStr);
            guiGraphics.drawString(mc.font, profitStr, left + width - profitWidth - 4, textY, profitColor);

            // Hover highlight
            if (isMouseOver) {
                guiGraphics.fill(left, top, left + width, top + height, 0x20FFFFFF);
            }
        }

        private static String shortPrice(long emeralds) {
            if (emeralds <= 0) return "?";
            if (emeralds >= 262144) {
                double stx = emeralds / 262144.0;
                String stxStr = Entry.formatPrice(emeralds);
                // Use the stx shorthand from the full format
                int parenIdx = stxStr.indexOf('(');
                if (parenIdx >= 0) return stxStr.substring(parenIdx + 1, stxStr.length() - 1);
            }
            return String.format("%,d", emeralds);
        }
    }
}
