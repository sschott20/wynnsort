package com.wynnsort.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe queue for crowdsource observations with built-in deduplication.
 * Uses a ConcurrentHashMap-backed set to prevent recording the same listing
 * (same item + price + quantity) multiple times within a flush interval.
 */
public class CrowdsourceQueue {

    public static final CrowdsourceQueue INSTANCE = new CrowdsourceQueue();

    /**
     * Set backed by ConcurrentHashMap for O(1) dedup + thread safety.
     * Uses CrowdsourceEntry.equals/hashCode based on deduplicationKey().
     */
    private final Set<CrowdsourceEntry> entries = ConcurrentHashMap.newKeySet();

    private CrowdsourceQueue() {}

    /**
     * Adds an observation to the queue. Duplicates (same item+price+quantity)
     * are silently ignored.
     *
     * @param entry the observation to add
     * @return true if the entry was added (not a duplicate)
     */
    public boolean add(CrowdsourceEntry entry) {
        if (entry == null || entry.itemName == null || entry.itemName.isEmpty()) {
            return false;
        }
        if (entry.listingPrice <= 0) {
            return false;
        }
        return entries.add(entry);
    }

    /**
     * Drains all entries from the queue and returns them as a list.
     * The queue is cleared atomically from the caller's perspective
     * (each entry is removed individually, but ConcurrentHashMap
     * guarantees visibility).
     *
     * @return list of all queued entries (may be empty, never null)
     */
    public List<CrowdsourceEntry> drain() {
        List<CrowdsourceEntry> result = new ArrayList<>(entries);
        entries.removeAll(result);
        return result;
    }

    /**
     * Returns the current number of queued entries.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns true if there are no queued entries.
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
