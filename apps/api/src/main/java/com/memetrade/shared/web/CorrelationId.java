package com.memetrade.shared.web;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

/** Access to the current {@code correlationId} (NFR9.2), kept in the logging MDC for the request or job cycle. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {}

    /** The current id; a new one is generated when no request or job set one (never empty). */
    public static String current() {
        return Optional.ofNullable(MDC.get(MDC_KEY))
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    /** Accepts a client-provided value only when it is a canonical UUID; otherwise generates a new one. */
    public static String acceptOrGenerate(String candidate) {
        if (candidate != null) {
            try {
                UUID parsed = UUID.fromString(candidate);
                if (parsed.toString().equalsIgnoreCase(candidate)) {
                    return candidate.toLowerCase();
                }
            } catch (IllegalArgumentException invalid) {
                // Not a UUID: fall through and generate a fresh id instead of trusting arbitrary client text.
            }
        }
        return UUID.randomUUID().toString();
    }

    /** Runs a scheduled job cycle with its own correlation id (observability-design.md). */
    public static <T> T withNew(Supplier<T> job) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, UUID.randomUUID().toString());
        try {
            return job.get();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    public static void runWithNew(Runnable job) {
        withNew(() -> {
            job.run();
            return null;
        });
    }
}
