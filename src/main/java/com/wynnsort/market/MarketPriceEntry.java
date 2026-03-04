package com.wynnsort.market;

public class MarketPriceEntry {
    public long price;
    public long timestamp;

    public MarketPriceEntry() {}

    public MarketPriceEntry(long price, long timestamp) {
        this.price = price;
        this.timestamp = timestamp;
    }
}
