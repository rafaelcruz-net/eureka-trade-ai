package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 261121-paper-real-unico U4, review R-02 (BR2.6; written before the fix): a failure of the checks before the read of
 * the own holdings (P1 to P3, here the read of the row of the bot) only affects the sale. The slot is never FAILED, a buy
 * of another token in the same cycle goes on as today, no SELL is recorded for the wallet, and the failure is counted
 * as READ_FAILED and logged once by the step.
 */
@Tag("u4-own-quantity-cycle")
class OwnHoldingsPrePhaseFailureIT extends AbstractOwnQuantityIT {

    private final Logger logger = (Logger) LoggerFactory.getLogger(OwnHoldingsStep.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    @Test
    void aFailureBeforeTheReadKeepsTheBuyOfTheCycle() {
        Pair pair = pair();
        String other = token("0.0001", 6, 5);
        OwnQuantityCycle cycle = cycle(true);
        doThrow(new DataAccessResourceFailureException("the database is down (test)"))
                .when(cycle.stepBots)
                .precheckRow(any());
        AtomicBoolean asked = new AtomicBoolean();
        cycle.buys(other, "1000", asked);

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(asked).isTrue();
        assertThat(result.action()).contains("BUY");
        assertThat(cycle.holdings.calls.get()).isZero();
        assertReadFailedCountedAndLogged(cycle);
    }

    @Test
    void aFailureBeforeTheReadSellsNothingOfTheWallet() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true);
        doThrow(new DataAccessResourceFailureException("the database is down (test)"))
                .when(cycle.stepBots)
                .slotClaimed(any(), any());
        cycle.sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(outcome(result)).isNotEqualTo("FAILED");
        assertThat(cycle.lastRequest().tokens()).noneMatch(t -> t.token().mint().equals(pair.xyz()));
        assertThat(sellDecisions(pair.a())).isEmpty();
        assertThat(context(result))
                .contains("\"" + pair.xyz() + "\": \"OWN_QUANTITY_UNAVAILABLE\"")
                .contains("\"reason\": \"READ_FAILED\"");
        assertReadFailedCountedAndLogged(cycle);
    }

    private void assertReadFailedCountedAndLogged(OwnQuantityCycle cycle) {
        assertThat(cycle.meters
                        .get("real_own_holdings_unavailable")
                        .tag("reason", "READ_FAILED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(cycle.meters.get("real_own_holdings_reads").counter().count())
                .isEqualTo(1.0);
        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("event=REAL_OWN_HOLDINGS_UNAVAILABLE"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage())
                            .contains("reason=READ_FAILED")
                            .contains("phase=PRE")
                            .doesNotContain("database is down");
                });
    }
}
