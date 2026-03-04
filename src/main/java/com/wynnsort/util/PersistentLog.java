package com.wynnsort.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Persistent log file that appends across game sessions.
 * Writes to config/wynnsort/wynnsort.log.
 */
public final class PersistentLog {

    private static final Path LOG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("wynnsort.log");
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long MAX_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    private static BufferedWriter writer;

    private PersistentLog() {}

    public static synchronized void init() {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            rotateIfNeeded();
            writer = Files.newBufferedWriter(LOG_PATH,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            write("INFO", "--- Session started ---");
        } catch (IOException e) {
            // Fall back silently; persistent logging is best-effort
        }
    }

    public static void info(String message, Object... args) {
        write("INFO", format(message, args));
    }

    public static void warn(String message, Object... args) {
        write("WARN", format(message, args));
    }

    public static void error(String message, Object... args) {
        write("ERROR", format(message, args));
    }

    private static synchronized void write(String level, String message) {
        if (writer == null) return;
        try {
            writer.write("[" + TIMESTAMP_FMT.format(LocalDateTime.now()) + "] [" + level + "] " + message);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {}
    }

    /**
     * SLF4J-style {} placeholder formatting.
     */
    private static String format(String template, Object... args) {
        if (args == null || args.length == 0) return template;
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int i = 0;
        while (i < template.length()) {
            if (i + 1 < template.length() && template.charAt(i) == '{' && template.charAt(i + 1) == '}') {
                if (argIdx < args.length) {
                    sb.append(args[argIdx++]);
                } else {
                    sb.append("{}");
                }
                i += 2;
            } else {
                sb.append(template.charAt(i));
                i++;
            }
        }
        // If last arg is a Throwable, append its message
        if (argIdx < args.length && args[args.length - 1] instanceof Throwable t) {
            sb.append(" | ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }

    private static void rotateIfNeeded() throws IOException {
        if (!Files.exists(LOG_PATH)) return;
        if (Files.size(LOG_PATH) > MAX_SIZE_BYTES) {
            Path old = LOG_PATH.resolveSibling("wynnsort.log.old");
            Files.deleteIfExists(old);
            Files.move(LOG_PATH, old);
        }
    }
}
