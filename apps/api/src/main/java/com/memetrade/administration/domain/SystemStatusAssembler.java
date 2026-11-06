package com.memetrade.administration.domain;

import com.memetrade.administration.domain.SystemStatus.Part;
import com.memetrade.administration.domain.SystemStatus.UnknownReason;
import com.memetrade.bots.api.BotState;
import com.memetrade.identity.api.IdentityCounts;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.SourceStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the system status (F5; BR3.1 to BR3.3; NFR3.2, NFR10.4): the six reads run in parallel on a pool of 6 threads,
 * each bounded by {@code perPart} (500 ms); an exception or the end of the time makes that part
 * {@code UNKNOWN(UNAVAILABLE | TIMEOUT)} and the others are shown. No default number ever replaces a missing count.
 *
 * <ol>
 *   <li>identity counts (guests and invitations, C17);
 *   <li>bots per state (C16);
 *   <li>the kill switch (own table);
 *   <li>the sources (C11);
 *   <li>pending audit events (AuditBacklog of U2);
 *   <li>rejected orders since 00:00 UTC of today (RejectionCounts of U6, BR3.2).
 * </ol>
 */
public class SystemStatusAssembler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusAssembler.class);

    private final Reads reads;
    private final Executor pool;
    private final Duration perPart;
    private final Clock clock;

    public SystemStatusAssembler(Reads reads, Executor pool, Duration perPart, Clock clock) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.perPart = Objects.requireNonNull(perPart, "perPart");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Stops the threads of the parts when the pool is owned here (an ExecutorService). */
    @Override
    public void close() {
        if (pool instanceof java.util.concurrent.ExecutorService owned) {
            owned.shutdownNow();
        }
    }

    /** Where each part comes from (the public interfaces of the owner modules). */
    public record Reads(
            Function<Principal, IdentityCounts> identity,
            Supplier<Map<BotState, Long>> bots,
            Supplier<KillSwitchSnapshot> killSwitch,
            Supplier<List<SourceStatus>> sources,
            LongSupplier auditOutboxPending,
            Function<Instant, Long> rejectedSince) {}

    public SystemStatus assemble(Principal admin) {
        Instant now = clock.instant();
        Instant startOfDay = now.truncatedTo(ChronoUnit.DAYS);
        CompletableFuture<IdentityCounts> identity =
                start("identity", () -> reads.identity().apply(admin));
        CompletableFuture<Map<BotState, Long>> bots = start("bots", reads.bots());
        CompletableFuture<KillSwitchSnapshot> killSwitch = start("killSwitch", reads.killSwitch());
        CompletableFuture<List<SourceStatus>> sources = start("sources", reads.sources());
        CompletableFuture<Long> backlog =
                start("auditOutboxPending", () -> reads.auditOutboxPending().getAsLong());
        CompletableFuture<Long> rejected =
                start("rejectedOrdersToday", () -> reads.rejectedSince().apply(startOfDay));
        Part<IdentityCounts> counts = part(identity);
        return new SystemStatus(
                now,
                map(counts, IdentityCounts::guestCount),
                map(counts, c -> Map.copyOf(c.inviteCounts())),
                part(bots),
                part(killSwitch),
                part(sources),
                part(backlog),
                part(rejected));
    }

    private <T> CompletableFuture<T> start(String name, Supplier<T> read) {
        return CompletableFuture.supplyAsync(() -> Objects.requireNonNull(read.get(), name), pool)
                .orTimeout(perPart.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((value, failure) -> {
                    if (failure != null) {
                        log.warn(
                                "System status part unknown: part={}, cause={}",
                                name,
                                unwrap(failure).getClass().getSimpleName());
                    }
                });
    }

    private static <T> Part<T> part(CompletableFuture<T> future) {
        try {
            return new Part.Known<>(future.join());
        } catch (CompletionException | java.util.concurrent.CancellationException failure) {
            return new Part.Unknown<>(
                    unwrap(failure) instanceof TimeoutException ? UnknownReason.TIMEOUT : UnknownReason.UNAVAILABLE);
        }
    }

    private static <T, R> Part<R> map(Part<T> part, Function<T, R> mapper) {
        return switch (part) {
            case Part.Known<T> known -> new Part.Known<>(mapper.apply(known.value()));
            case Part.Unknown<T> unknown -> new Part.Unknown<>(unknown.reason());
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
