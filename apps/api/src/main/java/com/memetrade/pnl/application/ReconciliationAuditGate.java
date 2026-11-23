package com.memetrade.pnl.application;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.infra.PnlMismatchAuditProperties;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.jdbc.ReadDeadline;
import com.memetrade.shared.web.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U2 (F4, BR8.7, BR3.4, NFR3.15, NFR3.16; security-design.md "Sequência"): what the REAL block
 * does with a reconciliation that did not match.
 *
 * <p>At once, on every read ({@link #onMismatch}):
 *
 * <ol>
 *   <li>{@code pnl_real_reconciliation_mismatches_total{check}} +1 per failed check (the alarm counts every read);
 *   <li>the log {@code REAL_PNL_RECONCILIATION_MISMATCH} (checks and window; never an address, a signature nor the
 *       differences, which stay in the event), at most once per minute per person and kind of check (decision of the
 *       founder of 2026-11-23, review R-05);
 *   <li>the answer {@link ReconciliationMismatch}, a {@link RealPnlUnavailable} with {@code RECONCILIATION_MISMATCH}:
 *       the REAL block UNAVAILABLE, no partial number. It carries the audit command, built now with the correlation id
 *       of the read.
 * </ol>
 *
 * <p>Only after the read transaction ended ({@link ReconciliationMismatch#audit()}; decision of the founder of
 * 2026-11-23, review R-01): the write of {@code REAL_PNL_RECONCILIATION_MISMATCH} at most once per hour per fingerprint
 * (SHA-256 of the person and the kinds of the failed checks; no value, no window), through
 * {@link RateLimitedAuditPublisher}, on an executor of its own (2 threads, a queue of 8, refused at once when full). The
 * caller waits at most {@code write-timeout} (200 ms), and the task gives up by itself past that deadline. A failure, a
 * late write or a full queue counts {@code pnl_real_reconciliation_audit_failures_total} and logs
 * {@code REAL_PNL_MISMATCH_AUDIT_FAILED}, and never changes the answer.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class ReconciliationAuditGate implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationAuditGate.class);

    static final int QUEUE = 8;

    /** Review R-05: the error log of a mismatch, at most once per this period per person and kind of check. */
    static final Duration LOG_EVERY = Duration.ofMinutes(1);

    /** The keys (person and check) kept for the limit of the log before the old ones are dropped. */
    static final int LOGGED_KEYS = 10_000;

    private final Map<String, Instant> lastLogged = new ConcurrentHashMap<>();

    private final RateLimitedAuditPublisher publisher;
    private final Clock clock;
    private final PnlRealMetrics metrics;
    private final Duration writeTimeout;
    private final ThreadPoolExecutor executor;

    @Autowired
    public ReconciliationAuditGate(
            RateLimitedAuditPublisher publisher,
            Clock clock,
            PnlRealMetrics metrics,
            PnlMismatchAuditProperties properties) {
        this.publisher = publisher;
        this.clock = clock;
        this.metrics = metrics;
        this.writeTimeout = properties.writeTimeout();
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                properties.poolSize(),
                properties.poolSize(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "pnl-mismatch-audit-" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Counts and logs the mismatch now and gives the failure the REAL block throws; its audit runs later, once the read
     * transaction ended ({@link ReconciliationMismatch#audit()}).
     */
    public ReconciliationMismatch onMismatch(UUID tenantId, UUID userId, ReconciliationResult result) {
        String fingerprint = fingerprint(userId, result);
        AuditEventCommand command = AuditEventCommand.person(
                tenantId,
                userId,
                AuditActions.REAL_PNL_RECONCILIATION_MISMATCH,
                "real-pnl",
                CorrelationId.current(),
                clock.instant(),
                details(result, fingerprint));
        metrics.mismatch(result.failedChecks());
        if (logDue(userId, result)) {
            log.error(
                    "event=REAL_PNL_RECONCILIATION_MISMATCH checks={} window={}",
                    result.failedChecks(),
                    window(result.window()));
        }
        return new ReconciliationMismatch(() -> write(command, fingerprint));
    }

    /**
     * Review R-05: true when at least one of the failed checks of the person was not logged in the last minute; marks
     * them. Bounded: past {@link #LOGGED_KEYS} keys, the keys older than a minute go first.
     */
    private boolean logDue(UUID userId, ReconciliationResult result) {
        Instant now = clock.instant();
        if (lastLogged.size() > LOGGED_KEYS) {
            lastLogged.values().removeIf(at -> !at.plus(LOG_EVERY).isAfter(now));
        }
        boolean due = false;
        for (ReconciliationResult.Check check : result.failedChecks()) {
            String key = userId + "|" + check.name();
            Instant previous = lastLogged.get(key);
            if (previous == null || !previous.plus(LOG_EVERY).isAfter(now)) {
                lastLogged.put(key, now);
                due = true;
            }
        }
        return due;
    }

    private String write(AuditEventCommand command, String fingerprint) {
        Duration budget = writeTimeout;
        Instant deadline = clock.instant().plus(budget);
        Future<RateLimitedAuditPublisher.Outcome> task;
        try {
            task = executor.submit(() -> publisher.publishOncePerHour(command, fingerprint, deadline));
        } catch (RejectedExecutionException full) {
            return failed("REJECTED", full.getClass().getSimpleName());
        }
        try {
            return switch (task.get(budget.toNanos(), TimeUnit.NANOSECONDS)) {
                case WRITTEN -> "true";
                case DEDUPLICATED, LOCK_BUSY -> "deduplicated";
            };
        } catch (TimeoutException late) {
            // The task checks the same deadline after it gets its connection and gives up without writing.
            task.cancel(false);
            return failed("DEADLINE", late.getClass().getSimpleName());
        } catch (ExecutionException failure) {
            return failed(category(failure.getCause()), simpleName(failure.getCause()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed("ERROR", interrupted.getClass().getSimpleName());
        }
    }

    private String failed(String category, String exceptionClass) {
        metrics.auditFailure();
        log.error("event=REAL_PNL_MISMATCH_AUDIT_FAILED category={} exceptionClass={}", category, exceptionClass);
        return "failed";
    }

    private static String category(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof RateLimitedAuditPublisher.DeadlinePassed) {
                return "DEADLINE";
            }
            if (t instanceof CannotGetJdbcConnectionException || t instanceof SQLTransientConnectionException) {
                return "CONNECTION_TIMEOUT";
            }
            if (ReadDeadline.isTimeout(t)) {
                return "DEADLINE";
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return "ERROR";
    }

    private static String simpleName(Throwable failure) {
        return failure == null ? "Unknown" : failure.getClass().getSimpleName();
    }

    /** ENT-214: the window, the checks, the differences in lamports and the fingerprint; nothing else. */
    static Map<String, String> details(ReconciliationResult result, String fingerprint) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("window", window(result.window()));
        details.put("checks", result.failedChecks().stream().map(Enum::name).collect(Collectors.joining(",")));
        result.diffs().forEach((check, lamports) -> details.put("diff." + check, Long.toString(lamports)));
        details.put("fingerprint", fingerprint);
        return details;
    }

    /**
     * The SHA-256 in lower-case hexadecimal of the canonical text of the mismatch (security-design.md; decision of the
     * founder of 2026-11-23, review R-05): the person and the kinds of the failed checks, never a value nor the window,
     * so a difference that changes at every read stays one event per hour.
     */
    public static String fingerprint(UUID userId, ReconciliationResult result) {
        String canonical = canonical(userId, result);
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    /** {@code <person>|<failed checks in alphabetical order>}. */
    static String canonical(UUID userId, ReconciliationResult result) {
        return userId + "|"
                + result.failedChecks().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static String window(PnlWindow window) {
        return switch (window) {
            case PnlWindow.All all -> "ALL";
            case PnlWindow.Range range -> "RANGE:" + range.from() + ":" + range.to();
        };
    }

    /** The tasks waiting in the queue (for the tests of saturation). */
    int queued() {
        return executor.getQueue().size();
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
