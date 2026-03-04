package com.wynnsort.history;

public class TransactionPair {
    public final TransactionRecord buy;
    public final TransactionRecord sell;
    public final long profitEmeralds;

    public TransactionPair(TransactionRecord buy, TransactionRecord sell, int buyTaxPercent) {
        this.buy = buy;
        this.sell = sell;
        long buyTotal = buy.priceEmeralds;
        if (buyTaxPercent > 0) buyTotal += buyTotal * buyTaxPercent / 100;
        this.profitEmeralds = (sell != null) ? sell.priceEmeralds - buyTotal : 0;
    }
}
