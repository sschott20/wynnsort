package com.wynnsort.util;

import java.util.Map;

/**
 * A single structured diagnostic event.
 */
public class DiagnosticEvent {
    public long timestamp;
    public String category;
    public String eventType;
    public Map<String, Object> data;
    public String threadName;

    public DiagnosticEvent() {}

    public DiagnosticEvent(long timestamp, String category, String eventType,
                           Map<String, Object> data, String threadName) {
        this.timestamp = timestamp;
        this.category = category;
        this.eventType = eventType;
        this.data = data;
        this.threadName = threadName;
    }

    /**
     * Returns a compact single-line summary of the data map for display.
     */
    public String dataSummary(int maxLength) {
        if (data == null || data.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            if (sb.length() > maxLength) {
                sb.setLength(maxLength - 3);
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }
}
