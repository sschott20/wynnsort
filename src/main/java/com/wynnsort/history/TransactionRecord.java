package com.wynnsort.history;

public class TransactionRecord {

    public enum Type { BUY, SELL }

    public String itemName;
    public long priceEmeralds;
    public Type type;
    public long timestamp;
    public String otherParty;
    public int quantity;
    /** Canonical item name from Wynntils itemInfo (e.g. "Yang" even for "Unidentified Yang") */
    public String baseName;
    /** Sorted stat fingerprint for identified gear: "apiName:value:stars,..." */
    public String statFingerprint;

    public TransactionRecord() {}

    public TransactionRecord(String itemName, long priceEmeralds, Type type,
                             String otherParty, int quantity) {
        this.itemName = itemName;
        this.priceEmeralds = priceEmeralds;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.otherParty = otherParty;
        this.quantity = quantity;
    }

    public TransactionRecord(String itemName, long priceEmeralds, Type type,
                             String otherParty, int quantity,
                             String baseName, String statFingerprint) {
        this(itemName, priceEmeralds, type, otherParty, quantity);
        this.baseName = baseName;
        this.statFingerprint = statFingerprint;
    }
}
