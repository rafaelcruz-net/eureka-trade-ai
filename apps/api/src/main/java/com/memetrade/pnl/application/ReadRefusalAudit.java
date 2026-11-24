package com.memetrade.pnl.application;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.identity.api.Principal;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U3 (decision D-NFR1 of the founder; NFR1.10, NFR1.15, NFR3.13, NFR3.14, NFR3.16;
 * security-design.md "Auditoria das recusas"): the audit of the refused reads of the P&L (a 404 of a bot of ANOTHER
 * tenant in C5 or C6, and the 422 {@code TEMPLATE_MISMATCH} of C6), limited to one event per hour per person and action
 * and NEVER delaying the answer.
 *
 * <ul>
 *   <li>the request thread only filters and submits, never waits: a single daemon thread {@code pnl-read-audit-1} and a
 *       bounded queue of {@value #QUEUE}, refused at once when full;
 *   <li>a filter in memory of the keys (person and action) submitted in the last {@link #SUBMITTED_WINDOW}, at most
 *       {@value #SUBMITTED_KEYS} keys (the oldest leaves first), saves the work of a repeated refusal; the trail stays
 *       the source of truth ({@link RateLimitedAuditPublisher#publishOncePerHour}, under its advisory lock);
 *   <li>the task writes under a deadline of {@link #BUDGET} counted from the submission by the injected clock;
 *   <li>a full queue or a failure counts {@code pnl.read_audit.failures{action}} and logs
 *       {@code PNL_READ_AUDIT_FAILED} at {@code ERROR}, at most once a minute per person and action, with the category
 *       and the simple name of the class, never the message; it does not mark the filter.
 * </ul>
 *
 * The details are only {@code route}, {@code side}, {@code botIds} and {@code fingerprint}: never a name, a strategy, a
 * value nor an address. The MDC of the request (the correlation id) travels to the task.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class ReadRefusalAudit implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReadRefusalAudit.class);

    static final int THREADS = 1;
    static final int QUEUE = 16;
    static final Duration BUDGET = Duration.ofMillis(200);
    static final Duration SUBMITTED_WINDOW = Duration.ofMinutes(60);
    static final int SUBMITTED_KEYS = 10_000;
    static final Duration LOG_EVERY = Duration.ofMinutes(1);

    private final RateLimitedAuditPublisher publisher;
    private final Clock clock;
    private final PnlReadAuditMetrics metrics;
    private final ThreadPoolExecutor executor;

    /** The keys submitted in the window, in order of submission (the oldest first). */
    private final Map<String, Instant> submitted = new LinkedHashMap<>();

    private final Map<String, Instant> lastLogged = new ConcurrentHashMap<>();

    public ReadRefusalAudit(RateLimitedAuditPublisher publisher, Clock clock, PnlReadAuditMetrics metrics) {
        this.publisher = publisher;
        this.clock = clock;
        this.metrics = metrics;
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                THREADS,
                THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "pnl-read-audit-" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** A 404 of a bot of another tenant (only when {@code ownedByAnotherTenant} answered true). */
    public void tenantMismatch(Principal person, String route, String side, List<UUID> botIds) {
        submit(person, ReadAuditAction.TENANT_MISMATCH, route, side, botIds);
    }

    /** A 422 {@code TEMPLATE_MISMATCH} of C6, after the ownership of the two bots. */
    public void templateMismatch(Principal person, String route, List<UUID> botIds) {
        submit(person, ReadAuditAction.TEMPLATE_MISMATCH, route, "BOTH", botIds);
    }

    private void submit(Principal person, ReadAuditAction action, String route, String side, List<UUID> botIds) {
        Instant now = clock.instant();
        String key = person.userId() + "|" + action.name();
        if (!mark(key, now)) {
            return;
        }
        String fingerprint = fingerprint(person.userId(), action);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("route", route);
        details.put("side", side);
        details.put("botIds", botIds.stream().map(UUID::toString).collect(Collectors.joining(",")));
        details.put("fingerprint", fingerprint);
        AuditEventCommand command = AuditEventCommand.person(
                person.tenantId(),
                person.userId(),
                action.auditAction(),
                "pnl-read",
                CorrelationId.current(),
                now,
                details);
        Instant deadline = now.plus(BUDGET);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> write(command, fingerprint, deadline, action, key, mdc));
        } catch (RejectedExecutionException full) {
            unmark(key);
            failed(person.userId(), action, "QUEUE_FULL", full);
        }
    }

    private void write(
            AuditEventCommand command,
            String fingerprint,
            Instant deadline,
            ReadAuditAction action,
            String key,
            Map<String, String> mdc) {
        if (mdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }
        try {
            if (!clock.instant().isBefore(deadline)) {
                throw new RateLimitedAuditPublisher.DeadlinePassed();
            }
            publisher.publishOncePerHour(command, fingerprint, deadline);
        } catch (RuntimeException failure) {
            unmark(key);
            failed(UUID.fromString(command.actor()), action, category(failure), failure);
        } finally {
            MDC.clear();
        }
    }

    /** True when the key was not submitted in the window; marks it (bounded: the oldest key leaves first). */
    synchronized boolean mark(String key, Instant now) {
        Iterator<Map.Entry<String, Instant>> oldest = submitted.entrySet().iterator();
        while (oldest.hasNext()) {
            Map.Entry<String, Instant> entry = oldest.next();
            if (entry.getValue().plus(SUBMITTED_WINDOW).isAfter(now)) {
                break;
            }
            oldest.remove();
        }
        if (submitted.containsKey(key)) {
            return false;
        }
        if (submitted.size() >= SUBMITTED_KEYS) {
            Iterator<String> first = submitted.keySet().iterator();
            first.next();
            first.remove();
        }
        submitted.put(key, now);
        return true;
    }

    private synchronized void unmark(String key) {
        submitted.remove(key);
    }

    /** The keys in the filter now (for the tests of its bound). */
    synchronized int submittedKeys() {
        return submitted.size();
    }

    private void failed(UUID userId, ReadAuditAction action, String category, Throwable failure) {
        metrics.failure(action);
        Instant now = clock.instant();
        String key = userId + "|" + action.name();
        if (lastLogged.size() > SUBMITTED_KEYS) {
            lastLogged.values().removeIf(at -> !at.plus(LOG_EVERY).isAfter(now));
        }
        Instant previous = lastLogged.get(key);
        if (previous == null || !previous.plus(LOG_EVERY).isAfter(now)) {
            lastLogged.put(key, now);
            log.error(
                    "event=PNL_READ_AUDIT_FAILED action={} category={} exceptionClass={}",
                    action,
                    category,
                    failure.getClass().getSimpleName());
        }
    }

    static String category(Throwable failure) {
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

    /** SHA-256 in lower-case hexadecimal of {@code pnl-read-audit|<person>|<action>}. */
    static String fingerprint(UUID userId, ReadAuditAction action) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(("pnl-read-audit|" + userId + "|" + action.name())
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
