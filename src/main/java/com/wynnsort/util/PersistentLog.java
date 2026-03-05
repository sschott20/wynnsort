package com.wynnsort.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Per-session log file with Minecraft-style rotation.
 * Current session writes to config/wynnsort/logs/latest.log.
 * On each new session, the previous latest.log is compressed to
 * {date}-{n}.log.gz and old archives are pruned.
 */
public final class PersistentLog {

    private static final Path LOGS_DIR = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("logs");
    private static final Path LOG_PATH = LOGS_DIR.resolve("latest.log");
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ARCHIVED_LOGS = 10;

    private static BufferedWriter writer;

    private PersistentLog() {}

    public static synchronized void init() {
        try {
            Files.createDirectories(LOGS_DIR);
            archivePreviousSession();
            // Start fresh — overwrite, not append
            writer = Files.newBufferedWriter(LOG_PATH,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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

    /**
     * Compresses the previous latest.log to {date}-{n}.log.gz, then prunes
     * old archives beyond MAX_ARCHIVED_LOGS.
     */
    private static void archivePreviousSession() {
        try {
            if (!Files.exists(LOG_PATH) || Files.size(LOG_PATH) == 0) return;

            String datePrefix = LocalDate.now().toString(); // e.g. 2026-03-05
            int seq = 1;
            Path archivePath;
            do {
                archivePath = LOGS_DIR.resolve(datePrefix + "-" + seq + ".log.gz");
                seq++;
            } while (Files.exists(archivePath));

            // Compress latest.log → archive
            try (var in = Files.newInputStream(LOG_PATH);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(archivePath))) {
                in.transferTo(out);
            }

            pruneOldArchives();
        } catch (IOException ignored) {
            // Best-effort — don't block startup
        }
    }

    private static void pruneOldArchives() throws IOException {
        List<Path> archives = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(LOGS_DIR, "*.log.gz")) {
            for (Path p : stream) {
                archives.add(p);
            }
        }

        if (archives.size() <= MAX_ARCHIVED_LOGS) return;

        // Sort oldest first (by filename, which is date-based)
        archives.sort(Comparator.comparing(p -> p.getFileName().toString()));

        int toRemove = archives.size() - MAX_ARCHIVED_LOGS;
        for (int i = 0; i < toRemove; i++) {
            Files.deleteIfExists(archives.get(i));
        }
    }
}
