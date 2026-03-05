package com.wynnsort.history;

public class TransactionRecord {

    public enum Type { BUY, SELL }

    public static final String CSV_HEADER = "type,itemName,baseName,quantity,priceEmeralds,timestamp,otherParty,statFingerprint";

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

    /**
     * Returns a CSV-formatted line representing this record.
     * Fields containing commas, quotes, or newlines are wrapped in double quotes
     * with internal quotes escaped by doubling.
     */
    public String toCsvLine() {
        return escapeCsv(type != null ? type.name() : "") + ","
                + escapeCsv(itemName) + ","
                + escapeCsv(baseName) + ","
                + quantity + ","
                + priceEmeralds + ","
                + timestamp + ","
                + escapeCsv(otherParty) + ","
                + escapeCsv(statFingerprint);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
