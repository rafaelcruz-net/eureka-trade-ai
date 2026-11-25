package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.realexecution.api.RealExecutionPort;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 261121-paper-real-unico U4, Etapa 4, T5 (written before the code; E8; BR2.2, BR2.3, BR2.5, BR2.6, NFR2.3): an
 * unavailable read fails closed and never breaks the cycle. For BUDGET_EXCEEDED, READ_FAILED (an error of the database
 * injected into the real read) and BOT_NOT_OWNED: no exception reaches the cycle, the note OWN_QUANTITY_UNAVAILABLE with
 * its reason is in the context, every mint of the wallet leaves the tokens, no SELL is recorded,
 * {@code real_own_holdings_unavailable_total{reason}} counts 1 and one log line is written (ERROR for BOT_NOT_OWNED);
 * the next cycle reads again; a slot too old does not read.
 */
@Tag("u4-own-quantity-cycle")
class OwnHoldingsUnavailableIT extends AbstractOwnQuantityIT {

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

    @ParameterizedTest
    @EnumSource(UnavailableReason.class)
    void anUnavailableReadSellsNothingOfTheWalletAndTheCycleEndsNormally(UnavailableReason reason) {
        Pair pair = pair();
        OwnQuantityCycle cycle = reason == UnavailableReason.READ_FAILED
                ? new OwnQuantityCycle(context, true, HoldingsReaders.of(context, failingOpenSells()))
                : cycle(true);
        if (reason != UnavailableReason.READ_FAILED) {
            cycle.holdings.forced = bot -> new HoldingsResult.Unavailable(reason);
        }
        cycle.sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(outcome(result)).isEqualTo("NO_ORDER");
        assertThat(cycle.lastRequest().tokens()).noneMatch(t -> t.token().mint().equals(pair.xyz()));
        assertThat(cycle.lastRequest().positions()).isEmpty();
        assertThat(sellDecisions(pair.a())).isEmpty();
        assertThat(context(result))
                .contains("\"" + pair.xyz() + "\": \"OWN_QUANTITY_UNAVAILABLE\"")
                .contains("\"reason\": \"" + reason.name() + "\"");
        assertThat(cycle.meters
                        .get("real_own_holdings_unavailable")
                        .tag("reason", reason.name())
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("event=REAL_OWN_HOLDINGS_UNAVAILABLE"))
                .singleElement()
                .satisfies(e -> assertThat(e.getLevel())
                        .isEqualTo(reason == UnavailableReason.BOT_NOT_OWNED ? Level.ERROR : Level.WARN));

        cycle.run(pair.refA(), nextSlot());
        assertThat(cycle.holdings.calls.get()).as("the next cycle reads again").isEqualTo(2);
    }

    @Test
    void aBuyOfAnotherTokenInTheSameCycleGoesOnAsToday() {
        Pair pair = pair();
        String other = token("0.0001", 6, 5);
        OwnQuantityCycle cycle = cycle(true);
        cycle.holdings.forced = bot -> new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED);
        AtomicBoolean asked = new AtomicBoolean();
        cycle.buys(other, "1000", asked);

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(asked).isTrue();
        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        // The buy goes the way of today (sized by the price of the token; the cut of the U4 never touches a buy).
        assertThat(result.action()).contains("BUY");
        assertThat(outcome(result)).isNotIn("REAL_OWN_QUANTITY_UNAVAILABLE", "REAL_NO_OWN_QUANTITY");
    }

    @Test
    void aSlotTooOldDoesNotRead() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        cycle.run(pair.refA(), slot().minus(Duration.ofMinutes(10)));

        assertThat(cycle.holdings.calls.get()).isZero();
    }

    /** The real port of the execution whose read of the open sales fails like a database down. */
    private RealExecutionPort failingOpenSells() {
        RealExecutionPort real = context.getBean(RealExecutionPort.class);
        return (RealExecutionPort) Proxy.newProxyInstance(
                RealExecutionPort.class.getClassLoader(),
                new Class<?>[] {RealExecutionPort.class},
                (proxy, m, args) -> {
                    if ("openSells".equals(m.getName())) {
                        throw new DataAccessResourceFailureException("the database is down (test)");
                    }
                    return m.invoke(real, args);
                });
    }
}
