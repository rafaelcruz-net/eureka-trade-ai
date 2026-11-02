package com.memetrade.riskassessment.infra;

import com.memetrade.marketdata.api.MarketCollectionCompleted;
import com.memetrade.marketdata.api.MarketRiskInputs;
import com.memetrade.marketdata.api.MarketRiskInputs.TokenInputs;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.domain.RiskEvaluator;
import com.memetrade.riskassessment.domain.RiskEvaluator.Evaluation;
import com.memetrade.riskassessment.domain.WeightSet;
import com.memetrade.riskassessment.infra.JdbcRiskAssessmentStore.Stamp;
import com.memetrade.riskassessment.infra.JdbcRiskAssessmentStore.Write;
import com.memetrade.shared.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F5 for every token observed in the last 24 hours (BR3.7, NFR2.2, NFR10.8).
 *
 * <ul>
 *   <li>Triggered after every collection ({@link MarketCollectionCompleted}) and after a weight activation; runs on the
 *       single {@code risk-recalc} thread. Triggers that arrive during a run are joined: one more run follows, never a
 *       queue of runs.
 *   <li>Batch read of the inputs (two queries), calculation in memory, writes in batches of 500 with one transaction
 *       each, only for tokens whose {@code inputsFingerprint} or version changed; the version guard of the store keeps
 *       an older version from overwriting a newer one.
 *   <li>A failure in a batch rolls that batch back, keeps the previous assessments, is logged and turns the health
 *       {@code riskRecalculation} DEGRADED; the next trigger tries again.
 *   <li>Each run holds the ShedLock lock {@code risk-recalculate} ({@code lockAtMostFor} 2 min).
 * </ul>
 */
public class RiskRecalculator {

    private static final Logger log = LoggerFactory.getLogger(RiskRecalculator.class);
    static final String LOCK = "risk-recalculate";
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

    private final MarketRiskInputs market;
    private final JdbcRiskWeightStore weights;
    private final JdbcRiskAssessmentStore assessments;
    private final TransactionTemplate transactions;
    private final LockingTaskExecutor locks;
    private final Executor executor;
    private final Clock clock;
    private final MeterRegistry meters;
    private final int batchSize;
    private final boolean automatic;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailure = new AtomicReference<>();
    private final AtomicBoolean lastFailed = new AtomicBoolean();
    private final MultiGauge bands;

    /** Everything the recalculation needs, wired by {@code RiskAssessmentConfig}. */
    public record Settings(
            MarketRiskInputs market,
            JdbcRiskWeightStore weights,
            JdbcRiskAssessmentStore assessments,
            TransactionTemplate transactions,
            LockingTaskExecutor locks,
            Executor executor,
            Clock clock,
            int batchSize,
            boolean automatic) {}

    /** Result of one run. */
    public record Run(int evaluated, int rewritten, int unavailable, boolean failed) {}

    public RiskRecalculator(Settings settings, MeterRegistry meters) {
        this.market = settings.market();
        this.weights = settings.weights();
        this.assessments = settings.assessments();
        this.transactions = settings.transactions();
        this.locks = settings.locks();
        this.executor = settings.executor();
        this.clock = settings.clock();
        this.meters = meters;
        this.batchSize = settings.batchSize();
        this.automatic = settings.automatic();
        this.bands = MultiGauge.builder("risk_assessments").register(meters);
    }

    /** F1 step 9: a collection finished (only where the scheduled jobs run; the tests call the recalculation). */
    @EventListener
    public void onCollection(MarketCollectionCompleted completed) {
        if (automatic) {
            requestRecalculation();
        }
    }

    /** F7 step 3: a weight version was activated and committed (same switch as the collection trigger). */
    public void onWeightsActivated() {
        if (automatic) {
            requestRecalculation();
        }
    }

    /** Asks for a recalculation; joins it with the run in progress, if any (BR3.7). */
    public void requestRecalculation() {
        pending.set(true);
        if (running.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    void drain() {
        try {
            while (pending.getAndSet(false)) {
                recalculateNow();
            }
        } finally {
            running.set(false);
        }
        if (pending.get() && running.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    /** One run through the lock, with its own correlation id; empty when another run holds the lock. */
    public Optional<Run> recalculateNow() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK, LOCK_AT_MOST_FOR, Duration.ZERO);
        try {
            LockingTaskExecutor.TaskResult<Run> result =
                    locks.executeWithLock(() -> CorrelationId.withNew(this::run), lock);
            return result.wasExecuted() ? Optional.of(result.getResult()) : Optional.empty();
        } catch (RuntimeException failure) {
            recordFailure(failure);
            return Optional.of(new Run(0, 0, 0, true));
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable checked) {
            throw new IllegalStateException("Risk recalculation failed with a checked exception", checked);
        }
    }

    Run run() {
        Instant started = clock.instant();
        Timer.Sample timer = Timer.start(meters);
        WeightSet active = weights.active();
        List<TokenInputs> tokens = market.recentTokens();
        Map<TokenId, Stamp> stamps = assessments.stamps();
        List<Write> changed = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        int unavailable = 0;
        for (TokenInputs inputs : tokens) {
            Evaluation evaluation = RiskEvaluator.evaluate(active, inputs);
            counts.merge(bandKey(evaluation), 1, Integer::sum);
            unavailable += evaluation.computation().available() ? 0 : 1;
            Stamp stamp = stamps.get(inputs.token());
            boolean same = stamp != null
                    && stamp.version() == active.version()
                    && stamp.fingerprint().equals(evaluation.fingerprint());
            boolean older = stamp != null && stamp.version() > active.version();
            if (!same && !older) {
                changed.add(new Write(inputs.token(), evaluation.computation(), evaluation.fingerprint(), started));
            }
        }
        int rewritten = 0;
        for (int from = 0; from < changed.size(); from += batchSize) {
            List<Write> batch = changed.subList(from, Math.min(changed.size(), from + batchSize));
            try {
                Integer written = transactions.execute(status -> assessments.write(batch));
                rewritten += written == null ? 0 : written;
            } catch (RuntimeException failure) {
                recordFailure(failure);
                timer.stop(Timer.builder("risk_recalculation_duration_seconds").register(meters));
                return new Run(tokens.size(), rewritten, unavailable, true);
            }
        }
        timer.stop(Timer.builder("risk_recalculation_duration_seconds").register(meters));
        recordSuccess(counts);
        log.info(
                "Risk recalculation finished: durationMs={}, evaluated={}, rewritten={}, unavailable={}",
                Duration.between(started, clock.instant()).toMillis(),
                tokens.size(),
                rewritten,
                unavailable);
        return new Run(tokens.size(), rewritten, unavailable, false);
    }

    public Optional<Instant> lastSuccess() {
        return Optional.ofNullable(lastSuccess.get());
    }

    /** True when the most recent run failed (its failure is newer than the last success). */
    public boolean lastRunFailed() {
        return lastFailed.get();
    }

    public Optional<Instant> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    private void recordSuccess(Map<String, Integer> counts) {
        lastSuccess.set(clock.instant());
        lastFailed.set(false);
        Counter.builder("risk_recalculation_total")
                .tag("result", "OK")
                .register(meters)
                .increment();
        bands.register(
                counts.entrySet().stream()
                        .map(entry -> MultiGauge.Row.of(tags(entry.getKey()), entry.getValue()))
                        .toList(),
                true);
    }

    private void recordFailure(RuntimeException failure) {
        lastFailure.set(clock.instant());
        lastFailed.set(true);
        Counter.builder("risk_recalculation_total")
                .tag("result", "FAILED")
                .register(meters)
                .increment();
        log.error(
                "Risk recalculation failed: exceptionClass={}",
                failure.getClass().getSimpleName());
    }

    private static String bandKey(Evaluation evaluation) {
        return evaluation.computation().available()
                ? "AVAILABLE/" + evaluation.computation().band().orElseThrow()
                : "UNAVAILABLE/NONE";
    }

    private static Tags tags(String key) {
        String[] parts = key.split("/");
        return Tags.of("status", parts[0], "band", parts[1]);
    }
}
