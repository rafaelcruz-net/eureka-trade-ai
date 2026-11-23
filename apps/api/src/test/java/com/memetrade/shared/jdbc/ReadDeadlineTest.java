package com.memetrade.shared.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.MutableClock;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * 261121-paper-real-unico U2, Etapa 6 (written before the code; NFR1.12, performance-design.md "Prazo duro dentro do
 * fluxo"): the hard deadline of a streamed read, on the injected clock, in two layers. Every row checks the clock and
 * past the limit throws {@link ReadDeadlineExceeded} before the row reaches the consumer; before the first fetch and at
 * every 500th row the remaining time is applied again as {@code set local statement_timeout}. Never a watchdog nor a
 * {@code Statement.cancel()}.
 */
class ReadDeadlineTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void theRemainingTimeIsTheLimitMinusTheClockAndNeverNegative() {
        ReadDeadline deadline = ReadDeadline.after(clock, Duration.ofMillis(250));

        clock.advance(Duration.ofMillis(100));
        assertThat(deadline.remaining()).isEqualTo(Duration.ofMillis(150));
        clock.advance(Duration.ofMillis(400));
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void theStatementTimeoutAppliedIsTheTimeThatRemains() {
        ReadDeadline deadline = ReadDeadline.after(clock, Duration.ofMillis(250));
        List<String> statements = new ArrayList<>();

        deadline.applyStatementTimeout(statements::add);
        clock.advance(Duration.ofMillis(180));
        deadline.applyStatementTimeout(statements::add);

        assertThat(statements).containsExactly("set local statement_timeout = 250", "set local statement_timeout = 70");
    }

    @Test
    void pastTheLimitNoStatementTimeoutIsAppliedAndTheReadStops() {
        ReadDeadline deadline = ReadDeadline.after(clock, Duration.ofMillis(250));
        List<String> statements = new ArrayList<>();
        clock.advance(Duration.ofMillis(250));

        assertThatThrownBy(() -> deadline.applyStatementTimeout(statements::add))
                .isInstanceOf(ReadDeadlineExceeded.class);
        assertThat(statements).isEmpty();
    }

    @Test
    void aRowPastTheLimitNeverReachesTheConsumer() throws Exception {
        ReadDeadline deadline = ReadDeadline.after(clock, Duration.ofMillis(250));
        List<Integer> delivered = new ArrayList<>();
        List<String> statements = new ArrayList<>();
        int[] row = {0};
        RowCallbackHandler guarded = deadline.guard(rs -> delivered.add(++row[0]), 500, statements::add);

        for (int i = 0; i < 500; i++) {
            guarded.processRow(null);
        }
        clock.advance(Duration.ofMillis(300));

        assertThatThrownBy(() -> guarded.processRow(null)).isInstanceOf(ReadDeadlineExceeded.class);
        assertThat(delivered).hasSize(500);
    }

    @Test
    void theStatementTimeoutIsAppliedAgainAtEveryBlockOfTheFetchSize() throws Exception {
        ReadDeadline deadline = ReadDeadline.after(clock, Duration.ofMillis(250));
        List<String> statements = new ArrayList<>();
        RowCallbackHandler guarded = deadline.guard(rs -> {}, 500, statements::add);

        for (int i = 1; i <= 1_200; i++) {
            if (i == 600) {
                clock.advance(Duration.ofMillis(50));
            }
            guarded.processRow(null);
        }

        assertThat(statements)
                .containsExactly("set local statement_timeout = 250", "set local statement_timeout = 200");
    }

    @Test
    void aCancelledStatementAndTheDeadlineAreBothATimeoutAndNothingElseIs() {
        SQLException cancelled = new SQLException("canceling statement due to statement timeout", "57014");
        SQLException other = new SQLException("relation does not exist", "42P01");

        assertThat(ReadDeadline.isTimeout(new ReadDeadlineExceeded(Duration.ofMillis(250))))
                .isTrue();
        assertThat(ReadDeadline.isTimeout(new QueryTimeoutException("timeout", cancelled)))
                .isTrue();
        assertThat(ReadDeadline.isTimeout(new RuntimeException("wrapped", cancelled)))
                .isTrue();
        assertThat(ReadDeadline.isTimeout(new RuntimeException("wrapped", other)))
                .isFalse();
        assertThat(ReadDeadline.isTimeout(new IllegalStateException("not a read")))
                .isFalse();
    }

    @Test
    void aBudgetOfZeroOrLessIsRefused() {
        assertThatThrownBy(() -> ReadDeadline.after(clock, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReadDeadline.after(clock, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
