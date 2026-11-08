package com.memetrade.riskcontrol.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.riskcontrol.infra.LoadtestTableStatistics;
import com.memetrade.riskcontrol.infra.LoadtestTableStatistics.TableState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passos 23 and 26 of U6 (volta 2, F10, NFR2.5): the wait of the seed for fresh statistics returns as soon as they are
 * fresh, and fails loudly, never silently, when autovacuum is off or does not analyze the tables in time. Fresh means,
 * for each of the two tables: no more modifications since the last analyze than the autovacuum analyze threshold, and,
 * when the seed wrote more rows to it than that threshold, a last analyze after the seed began (the cumulative counters
 * of PostgreSQL arrive late, so a low count alone may be an old one). Nothing is seeded by the wait cases here (zero
 * rows); LoadtestRiskSeedIT covers the wait over a real database.
 */
@Tag("u6")
class LoadtestRiskSeedStatisticsTest {

    private static final Instant SEED_STARTED = Instant.parse("2026-11-07T12:00:00Z");

    @Test
    void theSeedReturnsOnceTheStatisticsAreFresh() {
        Statistics statistics = new Statistics(true, 2);

        seed(statistics, Duration.ofMinutes(1)).seed(List.of(), 0, 0, 0);

        assertThat(statistics.polls.get()).isEqualTo(3);
    }

    @Test
    void theSeedReadsTheDatabaseNowBeforeItWritesOrPolls() {
        Statistics statistics = new Statistics(true, 0);

        seed(statistics, Duration.ofMinutes(1)).seed(List.of(), 0, 0, 0);

        assertThat(statistics.calls).first().isEqualTo("now");
        assertThat(statistics.calls).filteredOn("now"::equals).hasSize(1);
    }

    @Test
    void theSeedRefusesToWaitWhenAutovacuumIsOff() {
        Statistics statistics = new Statistics(false, 0);

        assertThatThrownBy(() -> seed(statistics, Duration.ofMinutes(1)).seed(List.of(), 0, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("autovacuum is off");
        assertThat(statistics.polls.get()).isZero();
    }

    @Test
    void theSeedFailsWhenAutovacuumDoesNotAnalyzeInTime() {
        Statistics statistics = new Statistics(true, Integer.MAX_VALUE);

        assertThatThrownBy(() -> seed(statistics, Duration.ofMillis(10)).seed(List.of(), 0, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not analyze order_evaluation and order_rejection");
    }

    @Test
    void aTimeoutThatIsNotPositiveIsRefused() {
        assertThatThrownBy(() -> seed(new Statistics(true, 0), Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("statistics-timeout must be positive");
    }

    /** The case of Passo 26: a low count of modifications is not enough after a big seed without a new analyze. */
    @Test
    void aBigSeedIsNotFreshUntilBothTablesWereAnalyzedAfterTheSeedBegan() {
        Map<String, Long> written = Map.of("order_evaluation", 8_000L, "order_rejection", 2_000L);
        Optional<Instant> before = Optional.of(SEED_STARTED.minusSeconds(1));
        Optional<Instant> after = Optional.of(SEED_STARTED.plusSeconds(1));

        assertThat(LoadtestRiskSeed.fresh(
                        List.of(state("order_evaluation", 0, 50, before), state("order_rejection", 0, 50, before)),
                        SEED_STARTED,
                        written))
                .isFalse();
        assertThat(LoadtestRiskSeed.fresh(
                        List.of(state("order_evaluation", 0, 850, after), state("order_rejection", 0, 50, before)),
                        SEED_STARTED,
                        written))
                .isFalse();
        assertThat(LoadtestRiskSeed.fresh(
                        List.of(
                                state("order_evaluation", 0, 850, Optional.empty()),
                                state("order_rejection", 0, 250, after)),
                        SEED_STARTED,
                        written))
                .isFalse();
        assertThat(LoadtestRiskSeed.fresh(
                        List.of(state("order_evaluation", 10, 850, after), state("order_rejection", 0, 250, after)),
                        SEED_STARTED,
                        written))
                .isTrue();
    }

    @Test
    void aSeedAtOrBelowTheThresholdOfATableNeedsNoNewAnalyzeOfIt() {
        Map<String, Long> written = Map.of("order_evaluation", 3L, "order_rejection", 50L);

        assertThat(LoadtestRiskSeed.fresh(
                        List.of(
                                state("order_evaluation", 3, 50, Optional.empty()),
                                state("order_rejection", 50, 50, Optional.empty())),
                        SEED_STARTED,
                        written))
                .isTrue();
    }

    @Test
    void aTableWithMoreModificationsThanTheThresholdIsNeverFresh() {
        Optional<Instant> after = Optional.of(SEED_STARTED.plusSeconds(1));

        assertThat(LoadtestRiskSeed.fresh(
                        List.of(state("order_evaluation", 851, 850, after), state("order_rejection", 0, 250, after)),
                        SEED_STARTED,
                        Map.of("order_evaluation", 0L, "order_rejection", 0L)))
                .isFalse();
    }

    @Test
    void aTableMissingFromTheStatisticsIsNeverFresh() {
        assertThat(LoadtestRiskSeed.fresh(
                        List.of(state("order_evaluation", 0, 50, Optional.of(SEED_STARTED.plusSeconds(1)))),
                        SEED_STARTED,
                        Map.of("order_evaluation", 0L, "order_rejection", 0L)))
                .isFalse();
    }

    private static TableState state(String table, long modified, long threshold, Optional<Instant> analyzed) {
        return new TableState(table, modified, threshold, analyzed);
    }

    private static LoadtestRiskSeed seed(LoadtestTableStatistics statistics, Duration timeout) {
        return new LoadtestRiskSeed(null, null, null, statistics, Clock.systemUTC(), timeout);
    }

    /** Statistics that turn fresh after {@code staleReads} reads (a count above the threshold until then). */
    private static final class Statistics extends LoadtestTableStatistics {

        private final boolean autovacuum;
        private final int staleReads;
        private final AtomicInteger polls = new AtomicInteger();
        private final List<String> calls = new ArrayList<>();

        Statistics(boolean autovacuum, int staleReads) {
            super(null);
            this.autovacuum = autovacuum;
            this.staleReads = staleReads;
        }

        @Override
        public boolean autovacuumOn() {
            return autovacuum;
        }

        @Override
        public Instant databaseNow() {
            calls.add("now");
            return SEED_STARTED;
        }

        @Override
        public List<TableState> seededTables() {
            calls.add("poll");
            long modified = polls.incrementAndGet() > staleReads ? 0 : 1_000;
            Optional<Instant> analyzed = Optional.of(SEED_STARTED.plusSeconds(1));
            return List.of(
                    state("order_evaluation", modified, 50, analyzed),
                    state("order_rejection", modified, 50, analyzed));
        }
    }
}
