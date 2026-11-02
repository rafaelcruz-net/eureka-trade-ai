package com.memetrade.marketdata.infra;

import com.memetrade.shared.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F6 (BR1.8, ADR-008, NFR2.3, NFR4.4): every hour deletes the history samples observed MORE than 24 hours ago (exactly
 * 24 h stays), in batches of {@value #BATCH} rows with one transaction per batch, under the ShedLock lock
 * {@code market-history-cleanup} ({@code lockAtMostFor} 10 min). Running it again changes nothing. Token rows are
 * never deleted: other Units reference them; a token without a recent observation simply leaves the list.
 */
@Component
public class MarketHistoryCleanup {

    private static final Logger log = LoggerFactory.getLogger(MarketHistoryCleanup.class);

    static final int BATCH = 10_000;
    static final Duration RETENTION = Duration.ofHours(24);
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(10);
    static final String LOCK = "market-history-cleanup";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LockingTaskExecutor locks;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicLong samples = new AtomicLong();

    public MarketHistoryCleanup(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            LockingTaskExecutor locks,
            Clock clock,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.locks = locks;
        this.clock = clock;
        this.meters = meters;
        Gauge.builder("market_history_samples", samples, AtomicLong::get).register(meters);
    }

    @Scheduled(cron = "0 7 * * * *", zone = "UTC")
    public void hourly() {
        try {
            runLocked();
        } catch (DataAccessException unavailable) {
            log.error(
                    "Market history cleanup failed: exceptionClass={}",
                    unavailable.getClass().getSimpleName());
        }
    }

    /** @return the number of samples deleted, or empty when another run holds the lock */
    public Optional<Long> runLocked() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK, LOCK_AT_MOST_FOR, Duration.ZERO);
        try {
            LockingTaskExecutor.TaskResult<Long> result =
                    locks.executeWithLock(() -> CorrelationId.withNew(this::run), lock);
            return result.wasExecuted() ? Optional.of(result.getResult()) : Optional.empty();
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            throw new IllegalStateException("Market history cleanup failed with a checked exception", checked);
        }
    }

    long run() {
        Instant started = clock.instant();
        Timestamp cutoff = Timestamp.from(started.minus(RETENTION));
        Timer.Sample timer = Timer.start(meters);
        long deleted = 0;
        int batch;
        do {
            batch = transactions.execute(status ->
                    jdbc.sql("""
                            delete from observation_sample where sample_id in (
                              select sample_id from observation_sample where observed_at < :cutoff limit :batch)
                            """).param("cutoff", cutoff).param("batch", BATCH).update());
            deleted += batch;
        } while (batch == BATCH);
        timer.stop(Timer.builder("market_history_cleanup_duration_seconds").register(meters));
        Counter.builder("market_history_cleanup_deleted_total").register(meters).increment(deleted);
        samples.set(jdbc.sql("select count(*) from observation_sample")
                .query(Long.class)
                .single());
        log.info(
                "Market history cleanup finished: durationMs={}, deleted={}",
                Duration.between(started, clock.instant()).toMillis(),
                deleted);
        return deleted;
    }
}
