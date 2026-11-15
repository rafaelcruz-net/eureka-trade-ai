package com.memetrade.marketdata.infra.coingecko;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.infra.coingecko.CallBudget.Decision;
import com.memetrade.marketdata.infra.coingecko.CallBudget.Kind;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Step 5 (BR1.3, NFR3.6, NFR3.7): the per-minute window, the monthly priorities and the conservative mode. */
class CallBudgetTest {

    private static final Instant START = Instant.parse("2026-11-14T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final MemoryUsage store = new MemoryUsage();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private CallBudget budget(int perMinute, int detailPerMinute, long monthly) {
        return new CallBudget("coingecko", perMinute, detailPerMinute, monthly, store, clock, meters);
    }

    @Test
    void theTotalPerMinuteLimitStopsTheCallsAndASlidingMinuteReleasesThem() {
        CallBudget budget = budget(3, 2, 1_000);

        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.ALLOWED);
        clock.advance(Duration.ofSeconds(20));
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.ALLOWED);
        assertThat(budget.acquire(Kind.DISCOVERY)).isEqualTo(Decision.ALLOWED);
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.MINUTE_LIMIT);

        clock.advance(Duration.ofSeconds(40));
        assertThat(budget.acquire(Kind.PRICE))
                .as("the first call left the window")
                .isEqualTo(Decision.ALLOWED);
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.MINUTE_LIMIT);
    }

    @Test
    void theDetailLimitOfTwoPerMinuteLeavesThePricesGoing() {
        CallBudget budget = budget(250, 2, 1_000);

        assertThat(budget.acquire(Kind.DETAIL)).isEqualTo(Decision.ALLOWED);
        assertThat(budget.acquire(Kind.DETAIL)).isEqualTo(Decision.ALLOWED);
        assertThat(budget.acquire(Kind.DETAIL)).isEqualTo(Decision.MINUTE_LIMIT);
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.ALLOWED);

        clock.advance(Duration.ofSeconds(61));
        assertThat(budget.acquire(Kind.DETAIL)).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void aCallRefusedByTheMinuteIsNotCountedInTheMonth() {
        CallBudget budget = budget(1, 2, 1_000);

        budget.acquire(Kind.PRICE);
        budget.acquire(Kind.PRICE);
        budget.acquire(Kind.PRICE);

        assertThat(store.calls("coingecko", "2026-11")).isEqualTo(1);
    }

    @Test
    void theCallIsCountedInTheMonthBeforeItIsMade() {
        CallBudget budget = budget(250, 2, 1_000);

        Decision decision = budget.acquire(Kind.PRICE);

        assertThat(decision.allowed()).isTrue();
        assertThat(store.calls("coingecko", "2026-11"))
                .as("counted when allowed")
                .isEqualTo(1);
        assertThat(budget.monthCalls()).isEqualTo(1);
    }

    @Test
    void detailsStopAtNinetyPercentDiscoveryAtNinetyFiveAndPricesAtNinetyEight() {
        CallBudget budget = budget(10_000, 10_000, 100);
        store.set("coingecko", "2026-11", 90);

        assertThat(budget.acquire(Kind.DETAIL)).isEqualTo(Decision.MONTH_BUDGET);
        assertThat(budget.acquire(Kind.DISCOVERY)).isEqualTo(Decision.ALLOWED);

        store.set("coingecko", "2026-11", 95);
        assertThat(budget.acquire(Kind.DISCOVERY)).isEqualTo(Decision.MONTH_BUDGET);
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.ALLOWED);

        store.set("coingecko", "2026-11", 98);
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.MONTH_BUDGET);
        assertThat(budget.acquire(Kind.USAGE))
                .as("the usage reading keeps the count right")
                .isEqualTo(Decision.ALLOWED);
    }

    @Test
    void anUnreadableCounterTurnsTheConservativeModeOnUntilItAnswersAgain() {
        CallBudget budget = budget(250, 2, 1_000);
        store.failing = true;

        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.CONSERVATIVE);
        assertThat(budget.conservative()).isTrue();
        assertThat(meters.get("marketdata_provider_conservative_mode")
                        .tag("source", "coingecko")
                        .gauge()
                        .value())
                .isEqualTo(1);

        store.failing = false;
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.ALLOWED);
        assertThat(budget.conservative()).isFalse();
        assertThat(meters.get("marketdata_provider_conservative_mode").gauge().value())
                .isZero();
    }

    @Test
    void theBudgetRatioGaugeIsTheMonthCountOverTheBudget() {
        CallBudget budget = budget(250, 2, 1_000);
        store.set("coingecko", "2026-11", 799);

        budget.acquire(Kind.PRICE);

        assertThat(meters.get("marketdata_provider_budget_ratio")
                        .tag("source", "coingecko")
                        .gauge()
                        .value())
                .isEqualTo(0.8);
    }

    @Test
    void aNewUtcMonthStartsANewCount() {
        clock.set(Instant.parse("2026-11-30T23:59:59Z"));
        CallBudget budget = budget(250, 2, 1_000);
        budget.acquire(Kind.PRICE);
        clock.set(Instant.parse("2026-12-01T00:00:00Z"));
        budget.acquire(Kind.PRICE);

        assertThat(store.calls("coingecko", "2026-11")).isEqualTo(1);
        assertThat(store.calls("coingecko", "2026-12")).isEqualTo(1);
        assertThat(CallBudget.month(Instant.parse("2026-01-01T00:00:00Z"))).isEqualTo("2026-01");
    }

    @Test
    void theReconciliationKeepsTheGreatestValueAndNeverLowersTheCount() {
        CallBudget budget = budget(250, 2, 1_000);
        store.set("coingecko", "2026-11", 300);

        assertThat(budget.reconcile(120)).hasValue(300);
        assertThat(budget.reconcile(450)).hasValue(450);
        assertThat(budget.monthCalls()).isEqualTo(450);
    }

    @Test
    void aRestartReadsTheStoredCountInsteadOfStartingAtZero() {
        store.set("coingecko", "2026-11", 1234);

        CallBudget restarted = budget(250, 2, 450_000);
        restarted.refresh();

        assertThat(restarted.monthCalls()).isEqualTo(1234);
        store.failing = true;
        restarted.refresh();
        assertThat(restarted.conservative()).isTrue();
        assertThat(restarted.reconcile(10)).isEmpty();
    }

    /** In-memory counter with the same conditional increment as the table. */
    static final class MemoryUsage implements UsageStore {

        private final Map<String, Long> rows = new HashMap<>();
        boolean failing;

        void set(String source, String month, long calls) {
            rows.put(source + "|" + month, calls);
        }

        @Override
        public OptionalLong increment(String source, String month, int stopPercent, long budget, Instant now) {
            fail();
            long current = rows.getOrDefault(source + "|" + month, 0L);
            if (rows.containsKey(source + "|" + month) && current * 100 >= (long) stopPercent * budget) {
                return OptionalLong.empty();
            }
            rows.put(source + "|" + month, current + 1);
            return OptionalLong.of(current + 1);
        }

        @Override
        public long raiseTo(String source, String month, long calls, Instant now) {
            fail();
            long stored = Math.max(rows.getOrDefault(source + "|" + month, 0L), calls);
            rows.put(source + "|" + month, stored);
            return stored;
        }

        @Override
        public long calls(String source, String month) {
            fail();
            return rows.getOrDefault(source + "|" + month, 0L);
        }

        private void fail() {
            if (failing) {
                throw new DataAccessResourceFailureException("down");
            }
        }
    }
}
