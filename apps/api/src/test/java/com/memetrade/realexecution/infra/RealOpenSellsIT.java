package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.OpenSellsFixture;
import com.memetrade.realexecution.api.OpenSell;
import com.memetrade.realexecution.api.OpenSellSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 261121-paper-real-unico U4, Etapa 3 (written before the code; C16 extended by D1, BR1.3, BR3.4, NFR3.1): the open
 * sales of a bot. A SELL order of the bot in progress (PROPOSED, QUOTED, SIGNED, SENT, AMBIGUOUS) counts with its input
 * base units; a PENDING SELL decision counts with its token base units, the deferred one included; anything final, any
 * BUY, and anything of another bot or another tenant never counts. The read writes nothing.
 */
@Tag("u4-own-quantity-cycle")
class RealOpenSellsIT extends AbstractRealExecutionIT {

    @ParameterizedTest
    @ValueSource(strings = {"PROPOSED", "QUOTED", "SIGNED", "SENT", "AMBIGUOUS"})
    void aSellOrderOfTheBotInProgressIsOpenWithItsInputBaseUnits(String state) {
        Setup setup = realBot("AUTO_FULL");
        UUID order = fixture()
                .order(setup.tenantId(), setup.userId(), setup.botId(), "SELL", setup.mint(), 60_000_000L, state);

        List<OpenSell> open = opens(() -> port.openSells(setup.tenantId(), setup.botId()));

        assertThat(open).containsExactly(new OpenSell(OpenSellSource.ORDER, order, setup.mint(), 60_000_000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "FAILED", "CANCELLED"})
    void aFinalSellOrderIsNeverOpen(String state) {
        Setup setup = realBot("AUTO_FULL");
        fixture().order(setup.tenantId(), setup.userId(), setup.botId(), "SELL", setup.mint(), 60_000_000L, state);

        assertThat(opens(() -> port.openSells(setup.tenantId(), setup.botId()))).isEmpty();
    }

    @Test
    void aPendingSellDecisionIsOpenTheDeferredOneIncluded() {
        Setup setup = realBot("AUTO_FULL");
        OpenSellsFixture fixture = fixture();
        UUID now = fixture.pendingSell(setup.tenantId(), setup.userId(), setup.botId(), setup.mint(), 60_000_000L);
        UUID deferred = fixture.decision(
                setup.tenantId(),
                setup.userId(),
                setup.botId(),
                "SELL",
                setup.mint(),
                15_000_000L,
                "PENDING",
                Instant.now().plus(Duration.ofMinutes(10)));

        List<OpenSell> open = opens(() -> port.openSells(setup.tenantId(), setup.botId()));

        assertThat(open)
                .containsExactlyInAnyOrder(
                        new OpenSell(OpenSellSource.DECISION_REQUEST, now, setup.mint(), 60_000_000L),
                        new OpenSell(OpenSellSource.DECISION_REQUEST, deferred, setup.mint(), 15_000_000L));
    }

    @Test
    void closedDecisionsAndBuysAreNeverOpen() {
        Setup setup = realBot("AUTO_FULL");
        OpenSellsFixture fixture = fixture();
        Instant now = Instant.now();
        fixture.decision(setup.tenantId(), setup.userId(), setup.botId(), "SELL", setup.mint(), 1, "PROCESSED", now);
        fixture.decision(setup.tenantId(), setup.userId(), setup.botId(), "SELL", setup.mint(), 1, "DISCARDED", now);
        fixture.decision(setup.tenantId(), setup.userId(), setup.botId(), "BUY", setup.mint(), 5, "PENDING", now);
        fixture.order(setup.tenantId(), setup.userId(), setup.botId(), "BUY", setup.mint(), 5, "SENT");

        assertThat(opens(() -> port.openSells(setup.tenantId(), setup.botId()))).isEmpty();
    }

    @Test
    void theSalesOfAnotherBotOrAnotherTenantNeverCount() {
        Setup setup = realBot("AUTO_FULL");
        Setup stranger = realBot("AUTO_FULL");
        UUID sibling = realBotRow(setup.tenantId(), setup.userId());
        OpenSellsFixture fixture = fixture();
        fixture.order(setup.tenantId(), setup.userId(), sibling, "SELL", setup.mint(), 9, "SENT");
        fixture.pendingSell(setup.tenantId(), setup.userId(), sibling, setup.mint(), 9);
        fixture.pendingSell(stranger.tenantId(), stranger.userId(), stranger.botId(), setup.mint(), 9);

        assertThat(opens(() -> port.openSells(setup.tenantId(), setup.botId()))).isEmpty();
        // The bot id of this person asked with the tenant of somebody else gives nothing either.
        fixture.pendingSell(setup.tenantId(), setup.userId(), setup.botId(), setup.mint(), 9);
        assertThat(opens(() -> port.openSells(stranger.tenantId(), setup.botId())))
                .isEmpty();
    }

    @Test
    void theReadWritesNothing() {
        Setup setup = realBot("AUTO_FULL");
        fixture().pendingSell(setup.tenantId(), setup.userId(), setup.botId(), setup.mint(), 9);
        org.springframework.transaction.support.TransactionTemplate readOnly =
                new org.springframework.transaction.support.TransactionTemplate(transactions.getTransactionManager());
        readOnly.setReadOnly(true);

        // A transaction that writes anything gets a transaction id; a read-only one refuses a write outright.
        Long writer = readOnly.execute(status -> {
            assertThat(port.openSells(setup.tenantId(), setup.botId())).hasSize(1);
            return writes();
        });

        assertThat(writer)
                .as("no transaction id was assigned: nothing was written")
                .isNull();
    }

    /** The read of C16 in a transaction of the test, as the snapshot of the own holdings calls it. */
    private List<OpenSell> opens(java.util.function.Supplier<List<OpenSell>> read) {
        return transactions.execute(status -> read.get());
    }

    private OpenSellsFixture fixture() {
        return new OpenSellsFixture(jdbc);
    }

    /** The transaction id of the current transaction if one was assigned (only a write assigns one), else null. */
    private Long writes() {
        return jdbc.sql("select txid_current_if_assigned()")
                .query((rs, row) -> (Long) rs.getObject(1, Long.class))
                .list()
                .get(0);
    }
}
