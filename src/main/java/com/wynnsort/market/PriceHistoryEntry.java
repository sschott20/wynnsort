package com.wynnsort.market;

public class PriceHistoryEntry {
    public long price; // in emeralds
    public long timestamp;

    public PriceHistoryEntry() {}

    public PriceHistoryEntry(long price, long timestamp) {
        this.price = price;
        this.timestamp = timestamp;
    }
}
