package com.wynnsort.market;

/**
 * A single crowdsourced market price observation.
 * Captures the full context of a trade market listing for aggregation.
 */
public class CrowdsourceEntry {
    public String itemName;
    public String itemType;         // "GearItem", "IngredientItem", etc.
    public String rarity;           // "Legendary", "Mythic", etc. or empty
    public long listingPrice;       // unit price in emeralds
    public int quantity;
    public boolean identified;
    public float overallPercentage; // -1 if N/A (unidentified or non-gear)
    public long timestamp;
    public String modVersion;

    public CrowdsourceEntry() {}

    public CrowdsourceEntry(String itemName, String itemType, String rarity,
                            long listingPrice, int quantity, boolean identified,
                            float overallPercentage, long timestamp, String modVersion) {
        this.itemName = itemName;
        this.itemType = itemType;
        this.rarity = rarity;
        this.listingPrice = listingPrice;
        this.quantity = quantity;
        this.identified = identified;
        this.overallPercentage = overallPercentage;
        this.timestamp = timestamp;
        this.modVersion = modVersion;
    }

    /**
     * Deduplication key: same item + price + quantity = same observation.
     * This prevents recording the same listing multiple times when
     * the container content event fires repeatedly.
     */
    public String deduplicationKey() {
        return itemName + "|" + listingPrice + "|" + quantity;
    }

    @Override
    public int hashCode() {
        return deduplicationKey().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CrowdsourceEntry other)) return false;
        return deduplicationKey().equals(other.deduplicationKey());
    }
}
