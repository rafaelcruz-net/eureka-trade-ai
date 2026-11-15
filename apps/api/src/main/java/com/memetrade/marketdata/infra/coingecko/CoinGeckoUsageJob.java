package com.memetrade.marketdata.infra.coingecko;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoMetrics.Outcome;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * NFR3.7 (infrastructure-specification.md "Leitura do uso"): shortly after the worker starts and then every hour,
 * under the ShedLock lock {@value #LOCK}, reads the usage of the key from CoinGecko and keeps in the counter the
 * greatest value between the table and the provider. The reading itself goes through {@link CallBudget} (kind
 * {@code usage}). Worker only, created by {@link CoinGeckoConfig} when {@code MARKET_SOURCES} names coingecko.
 */
@ProcessRole(ProcessRole.Kind.WORKER)
public class CoinGeckoUsageJob {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoUsageJob.class);

    static final String LOCK = "marketdata-coingecko-usage";
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

    private final CoinGeckoHttp http;
    private final CallBudget budget;
    private final LockingTaskExecutor locks;
    private final CoinGeckoMetrics metrics;
    private final Clock clock;

    public CoinGeckoUsageJob(
            CoinGeckoHttp http, CallBudget budget, LockingTaskExecutor locks, CoinGeckoMetrics metrics, Clock clock) {
        this.http = http;
        this.budget = budget;
        this.locks = locks;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 3_600_000, initialDelay = 5_000)
    public void hourly() {
        try {
            runLocked();
        } catch (DataAccessException unavailable) {
            log.error(
                    "Market provider usage job failed: exceptionClass={}",
                    unavailable.getClass().getSimpleName());
        }
    }

    /** @return the stored count after the reconciliation; empty when skipped, refused or failed */
    public OptionalLong runLocked() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK, LOCK_AT_MOST_FOR, Duration.ZERO);
        try {
            LockingTaskExecutor.TaskResult<OptionalLong> result =
                    locks.executeWithLock(() -> CorrelationId.withNew(this::run), lock);
            return result.wasExecuted() ? result.getResult() : OptionalLong.empty();
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            throw new IllegalStateException("Market provider usage job failed with a checked exception", checked);
        }
    }

    OptionalLong run() {
        CallBudget.Decision decision = budget.acquire(CallBudget.Kind.USAGE);
        if (!decision.allowed()) {
            metrics.call(CallBudget.Kind.USAGE, Outcome.LIMITED);
            log.warn("Market provider usage not read: decision={}", decision);
            return OptionalLong.empty();
        }
        JsonNode usage;
        try {
            usage = http.keyUsage();
        } catch (CoinGeckoCallException failure) {
            metrics.call(CallBudget.Kind.USAGE, Outcome.ERROR);
            log.warn("Market provider usage not read: {}", failure.getMessage());
            return OptionalLong.empty();
        }
        metrics.call(CallBudget.Kind.USAGE, Outcome.OK);
        OptionalLong reported = monthlyCalls(usage);
        if (reported.isEmpty()) {
            log.warn("Market provider usage answer without a monthly call count");
            return OptionalLong.empty();
        }
        OptionalLong stored = budget.reconcile(reported.getAsLong());
        stored.ifPresent(calls ->
                log.info("Market provider usage reconciled: reported={}, stored={}", reported.getAsLong(), calls));
        return stored;
    }

    /** {@code current_total_monthly_calls} as a whole number at least 0, text or number. */
    static OptionalLong monthlyCalls(JsonNode usage) {
        JsonNode node = usage == null ? null : usage.get("current_total_monthly_calls");
        if (node == null || node.isNull()) {
            return OptionalLong.empty();
        }
        if (node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0) {
            return OptionalLong.of(node.longValue());
        }
        if (node.isTextual() && node.textValue().trim().matches("\\d{1,15}")) {
            return OptionalLong.of(Long.parseLong(node.textValue().trim()));
        }
        return OptionalLong.empty();
    }
}
