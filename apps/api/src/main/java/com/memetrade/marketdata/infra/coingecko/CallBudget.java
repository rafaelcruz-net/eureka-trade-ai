package com.memetrade.marketdata.infra.coingecko;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Quota and budget of the CoinGecko calls (BR1.3, NFR3.6, NFR3.7, reliability-design.md "Orçamento"). Every call asks
 * here first:
 *
 * <ol>
 *   <li><b>per minute</b>, in a sliding window of 60 s measured with the injectable clock: at most
 *       {@code callsPerMinute} calls in total (250) and {@code detailCallsPerMinute} detail calls (2). Above it the call
 *       is not made and the item waits for a later round ({@link Decision#MINUTE_LIMIT});
 *   <li><b>per month</b>, in {@code marketdata_provider_usage}: the call is counted BEFORE it is opened, in its own
 *       transaction, only while the month stays under the priority threshold of its kind: details stop at 90% of the
 *       budget, the discovery at 95%, the prices at 98% (2% of margin), the usage reading never
 *       ({@link Decision#MONTH_BUDGET});
 *   <li><b>conservative mode</b>: when the table cannot be read or written, no call is made at all and the round ends
 *       DEGRADED ({@link Decision#CONSERVATIVE}); gauge {@code marketdata_provider_conservative_mode} is 1 until the
 *       table answers again.
 * </ol>
 *
 * Gauge {@code marketdata_provider_budget_ratio{source}} is the last known count of the month over the budget.
 */
public class CallBudget {

    private static final Logger log = LoggerFactory.getLogger(CallBudget.class);

    static final Duration WINDOW = Duration.ofMinutes(1);

    /** The kind of a call with the share of the monthly budget after which it stops (percent). */
    public enum Kind {
        PRICE(98),
        DISCOVERY(95),
        DETAIL(90),
        USAGE(100);

        private final int stopPercent;

        Kind(int stopPercent) {
            this.stopPercent = stopPercent;
        }

        public int stopPercent() {
            return stopPercent;
        }

        /** Metric label ({@code kind} of {@code marketdata_provider_calls_total}). */
        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The answer for one call. */
    public enum Decision {
        ALLOWED,
        MINUTE_LIMIT,
        MONTH_BUDGET,
        CONSERVATIVE;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    private final String source;
    private final int callsPerMinute;
    private final int detailCallsPerMinute;
    private final long monthlyBudget;
    private final UsageStore store;
    private final Clock clock;
    private final Deque<Instant> calls = new ArrayDeque<>();
    private final Deque<Instant> detailCalls = new ArrayDeque<>();
    private final AtomicLong monthCalls = new AtomicLong();
    private final AtomicBoolean conservative = new AtomicBoolean();

    public CallBudget(
            String source,
            int callsPerMinute,
            int detailCallsPerMinute,
            long monthlyBudget,
            UsageStore store,
            Clock clock,
            MeterRegistry meters) {
        this.source = source;
        this.callsPerMinute = callsPerMinute;
        this.detailCallsPerMinute = detailCallsPerMinute;
        this.monthlyBudget = monthlyBudget;
        this.store = store;
        this.clock = clock;
        Gauge.builder("marketdata_provider_budget_ratio", this, CallBudget::budgetRatio)
                .tag("source", source)
                .register(meters);
        Gauge.builder("marketdata_provider_conservative_mode", conservative, flag -> flag.get() ? 1 : 0)
                .tag("source", source)
                .register(meters);
    }

    /** Decides one call of {@code kind}; when ALLOWED the call is already counted in the month. */
    public synchronized Decision acquire(Kind kind) {
        Instant now = clock.instant();
        forget(calls, now);
        forget(detailCalls, now);
        if (calls.size() >= callsPerMinute || (kind == Kind.DETAIL && detailCalls.size() >= detailCallsPerMinute)) {
            return Decision.MINUTE_LIMIT;
        }
        OptionalLong counted;
        try {
            counted = store.increment(source, month(now), kind.stopPercent(), monthlyBudget, now);
        } catch (DataAccessException unavailable) {
            if (!conservative.getAndSet(true)) {
                log.error(
                        "Market provider call counter unavailable, conservative mode on: source={}, exceptionClass={}",
                        source,
                        unavailable.getClass().getSimpleName());
            }
            return Decision.CONSERVATIVE;
        }
        leaveConservativeMode();
        if (counted.isEmpty()) {
            return Decision.MONTH_BUDGET;
        }
        monthCalls.set(counted.getAsLong());
        calls.addLast(now);
        if (kind == Kind.DETAIL) {
            detailCalls.addLast(now);
        }
        return Decision.ALLOWED;
    }

    /**
     * NFR3.7: keeps the greatest value between the table and the count reported by the provider for this month.
     *
     * @return the stored count after the reconciliation, or empty in conservative mode
     */
    public synchronized OptionalLong reconcile(long providerCalls) {
        Instant now = clock.instant();
        try {
            long stored = store.raiseTo(source, month(now), Math.max(0, providerCalls), now);
            leaveConservativeMode();
            monthCalls.set(stored);
            return OptionalLong.of(stored);
        } catch (DataAccessException unavailable) {
            conservative.set(true);
            log.error(
                    "Market provider usage could not be reconciled: source={}, exceptionClass={}",
                    source,
                    unavailable.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    /** Reads the stored count of the month (start-up), so a restart never resets it. */
    public synchronized void refresh() {
        Instant now = clock.instant();
        try {
            monthCalls.set(store.calls(source, month(now)));
            leaveConservativeMode();
        } catch (DataAccessException unavailable) {
            conservative.set(true);
            log.error(
                    "Market provider call counter could not be read: source={}, exceptionClass={}",
                    source,
                    unavailable.getClass().getSimpleName());
        }
    }

    public boolean conservative() {
        return conservative.get();
    }

    public long monthCalls() {
        return monthCalls.get();
    }

    /** Last known count of the month over the budget, rounded to 4 decimals (a metric, never a money value). */
    double budgetRatio() {
        return BigDecimal.valueOf(monthCalls.get())
                .divide(BigDecimal.valueOf(monthlyBudget), 4, RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    /** The UTC calendar month {@code YYYY-MM} of an instant. */
    static String month(Instant at) {
        return YearMonth.from(at.atZone(ZoneOffset.UTC)).toString();
    }

    private void leaveConservativeMode() {
        if (conservative.getAndSet(false)) {
            log.info("Market provider call counter available again: source={}", source);
        }
    }

    private static void forget(Deque<Instant> window, Instant now) {
        Instant start = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(start)) {
            window.removeFirst();
        }
    }
}
