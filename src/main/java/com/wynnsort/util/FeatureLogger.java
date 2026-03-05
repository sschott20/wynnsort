package com.wynnsort.util;

import com.wynnsort.WynnSortMod;

import java.util.Map;

/**
 * Lightweight feature-specific logger that auto-prepends a [WS:Tag] prefix
 * and optionally emits DiagnosticLog events.
 *
 * <p>Usage: each feature creates one at the class level:
 * <pre>
 *   private static final FeatureLogger LOG = new FeatureLogger("TM", DiagnosticLog.Category.TRADE_MARKET);
 * </pre>
 * Then calls {@code LOG.info("State changed: {}", newState);} which outputs
 * {@code [WS:TM] State changed: BUYING} in the persistent log.
 */
public final class FeatureLogger {

    private final String prefix;
    private final DiagnosticLog.Category defaultCategory;

    public FeatureLogger(String tag, DiagnosticLog.Category defaultCategory) {
        this.prefix = "[WS:" + tag + "] ";
        this.defaultCategory = defaultCategory;
    }

    public void info(String msg, Object... args) {
        WynnSortMod.log(prefix + msg, args);
    }

    public void warn(String msg, Object... args) {
        WynnSortMod.logWarn(prefix + msg, args);
    }

    public void error(String msg, Object... args) {
        WynnSortMod.logError(prefix + msg, args);
    }

    /** Log an info message and also emit a DiagnosticLog event with the default category. */
    public void event(String eventType, Map<String, Object> data) {
        DiagnosticLog.event(defaultCategory, eventType, data);
    }

    /** Log an info message and also emit a DiagnosticLog event with a specific category. */
    public void event(DiagnosticLog.Category category, String eventType, Map<String, Object> data) {
        DiagnosticLog.event(category, eventType, data);
    }
}
