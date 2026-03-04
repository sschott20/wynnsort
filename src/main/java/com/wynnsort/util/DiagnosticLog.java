package com.wynnsort.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wynnsort.config.WynnSortConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Structured diagnostic logging system that outputs JSON events for automated analysis.
 *
 * Events are stored in a ring buffer in memory (last 1000 events) and also written
 * to a JSONL file (config/wynnsort/diagnostics.jsonl) with 2MB rotation.
 */
public final class DiagnosticLog {

    public enum Category {
        TRADE_MARKET, LOOTRUN, OVERLAY, BEACON, CONFIG, STARTUP, ERROR
    }

    private static final int RING_BUFFER_SIZE = 1000;
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2 MB

    private static final Path DIAGNOSTICS_DIR = FabricLoader.getInstance().getConfigDir().resolve("wynnsort");
    private static final Path DIAGNOSTICS_PATH = DIAGNOSTICS_DIR.resolve("diagnostics.jsonl");
    private static final Path DIAGNOSTICS_OLD_PATH = DIAGNOSTICS_DIR.resolve("diagnostics.jsonl.old");

    private static final Gson GSON = new GsonBuilder().create();

    // Ring buffer
    private static final DiagnosticEvent[] buffer = new DiagnosticEvent[RING_BUFFER_SIZE];
    private static int writeIndex = 0;
    private static int count = 0;

    private static final ReentrantLock lock = new ReentrantLock();

    private static BufferedWriter fileWriter;
    private static boolean initialized = false;

    private DiagnosticLog() {}

    /**
     * Initialize the diagnostic log system. Must be called before any event() calls.
     * Safe to call multiple times.
     */
    public static void init() {
        lock.lock();
        try {
            if (initialized) return;
            try {
                Files.createDirectories(DIAGNOSTICS_DIR);
                rotateIfNeeded();
                fileWriter = Files.newBufferedWriter(DIAGNOSTICS_PATH,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                initialized = true;
            } catch (IOException e) {
                // Best-effort; file logging will be disabled but memory buffer still works
                initialized = true;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Log a structured diagnostic event.
     *
     * @param category Event category
     * @param eventType Short event type identifier (e.g., "price_recorded", "state_change")
     * @param data Key-value data associated with the event
     */
    public static void event(Category category, String eventType, Map<String, Object> data) {
        if (!WynnSortConfig.INSTANCE.diagnosticLoggingEnabled) return;

        long now = System.currentTimeMillis();
        String thread = Thread.currentThread().getName();

        DiagnosticEvent evt = new DiagnosticEvent(now, category.name(), eventType, data, thread);

        lock.lock();
        try {
            // Write to ring buffer
            buffer[writeIndex] = evt;
            writeIndex = (writeIndex + 1) % RING_BUFFER_SIZE;
            if (count < RING_BUFFER_SIZE) count++;

            // Write to file
            writeToFile(evt);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the most recent events, newest first.
     */
    public static List<DiagnosticEvent> getRecentEvents(int maxCount) {
        lock.lock();
        try {
            int n = Math.min(maxCount, count);
            List<DiagnosticEvent> result = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int idx = (writeIndex - 1 - i + RING_BUFFER_SIZE) % RING_BUFFER_SIZE;
                DiagnosticEvent evt = buffer[idx];
                if (evt != null) {
                    result.add(evt);
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the most recent events for a specific category, newest first.
     */
    public static List<DiagnosticEvent> getRecentEvents(Category category, int maxCount) {
        lock.lock();
        try {
            String catName = category.name();
            List<DiagnosticEvent> result = new ArrayList<>();
            for (int i = 0; i < count && result.size() < maxCount; i++) {
                int idx = (writeIndex - 1 - i + RING_BUFFER_SIZE) % RING_BUFFER_SIZE;
                DiagnosticEvent evt = buffer[idx];
                if (evt != null && catName.equals(evt.category)) {
                    result.add(evt);
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Export events to a file. Returns the path written, or null on failure.
     */
    public static Path exportEvents(List<DiagnosticEvent> events) {
        Path exportPath = DIAGNOSTICS_DIR.resolve("diagnostics-export-" + System.currentTimeMillis() + ".jsonl");
        try {
            Files.createDirectories(DIAGNOSTICS_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(exportPath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (DiagnosticEvent evt : events) {
                    writer.write(toJsonLine(evt));
                    writer.newLine();
                }
            }
            return exportPath;
        } catch (IOException e) {
            return null;
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private static void writeToFile(DiagnosticEvent evt) {
        if (fileWriter == null) return;
        try {
            fileWriter.write(toJsonLine(evt));
            fileWriter.newLine();
            fileWriter.flush();

            // Check rotation after write
            if (Files.exists(DIAGNOSTICS_PATH) && Files.size(DIAGNOSTICS_PATH) > MAX_FILE_SIZE) {
                fileWriter.close();
                rotateIfNeeded();
                fileWriter = Files.newBufferedWriter(DIAGNOSTICS_PATH,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // Best-effort file writing
        }
    }

    private static String toJsonLine(DiagnosticEvent evt) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", evt.timestamp);
        line.put("cat", evt.category);
        line.put("evt", evt.eventType);
        line.put("data", evt.data != null ? evt.data : Map.of());
        line.put("thread", evt.threadName);
        return GSON.toJson(line);
    }

    private static void rotateIfNeeded() throws IOException {
        if (!Files.exists(DIAGNOSTICS_PATH)) return;
        if (Files.size(DIAGNOSTICS_PATH) > MAX_FILE_SIZE) {
            Files.deleteIfExists(DIAGNOSTICS_OLD_PATH);
            Files.move(DIAGNOSTICS_PATH, DIAGNOSTICS_OLD_PATH);
        }
    }
}
