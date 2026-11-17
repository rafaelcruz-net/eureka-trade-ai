package com.memetrade.realexits.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.realexecution.api.PositionExitGuard;
import com.memetrade.realexecution.api.PositionExitGuard.ExitRelease;
import com.memetrade.realexits.AbstractRealExitsIT;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Step 5 of the U7, written BEFORE the code (BR4.3, BR6.5, U7-NFR4.1, U7-NFR4.3, logical-components.md "Venda atrás do
 * PositionExitGuard"): before a market sale the guard reads the conditional of the position and answers READY or WAIT,
 * in the transaction of the caller, without any call to the network.
 */
@Tag("u7-exits-and-fiscal")
class ExitGuardIT extends AbstractRealExitsIT {

    @Autowired
    private PositionExitGuard guard;

    private ExitRelease release(Setup setup) {
        return transactions.execute(status -> guard.releaseForSale(setup.tenantId(), setup.userId(), setup.mint()));
    }

    @Test
    void withoutAConditionalThePositionIsReady() {
        Setup setup = realBot("AUTO_EXITS");

        assertThat(release(setup)).isEqualTo(ExitRelease.READY);
        assertThat(trigger.calls).hasValue(0);
    }

    /** BR6.5: a conditional waiting for the signature is cancelled at once, without a transaction. */
    @Test
    void aPendingSignatureIsCancelledDirectlyAndThePositionIsReady() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "PENDING_SIGNATURE", 5_000L);

        assertThat(release(setup)).isEqualTo(ExitRelease.READY);
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLED");
        assertThat(cancelReason(conditional)).isEqualTo("MARKET_SELL");
        assertThat(trigger.calls).hasValue(0);
    }

    /** S19: an OPEN conditional is marked CANCELLING (only the database) and the sale waits. */
    @Test
    void anOpenConditionalIsMarkedCancellingAndTheSaleWaits() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "OPEN", 5_000L);

        assertThat(release(setup)).isEqualTo(ExitRelease.WAIT);
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLING");
        assertThat(cancelReason(conditional)).isEqualTo("MARKET_SELL");
        assertThat(trigger.calls).as("the guard never calls the network").hasValue(0);
    }

    @Test
    void aConditionalAlreadyCancellingKeepsItsReasonAndTheSaleWaits() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "CANCELLING", 5_000L);

        assertThat(release(setup)).isEqualTo(ExitRelease.WAIT);
        assertThat(cancelReason(conditional)).isEqualTo("USER");
    }

    /** A final conditional does not hold the position: a new sale goes on. */
    @Test
    void aFinalConditionalDoesNotHoldThePosition() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        jdbc.sql("update real_conditional_order set state = 'FILLED', filled_side = 'STOP', final_at = now()"
                        + " where conditional_order_id = :id")
                .param("id", conditional)
                .update();

        assertThat(release(setup)).isEqualTo(ExitRelease.READY);
    }

    /** team.md: the conditional of another person on the same mint is never read nor touched. */
    @Test
    void theConditionalOfAnotherPersonOnTheSameMintIsNeverTouched() {
        Setup mine = realBot("AUTO_EXITS");
        Setup other = realBot("AUTO_EXITS");
        UUID theirs = conditional(
                new Setup(
                        other.tenantId(),
                        other.userId(),
                        other.botWalletId(),
                        other.botId(),
                        other.bot(),
                        other.main(),
                        mine.mint()),
                "OPEN",
                5_000L);

        assertThat(release(mine)).isEqualTo(ExitRelease.READY);
        assertThat(conditionalState(theirs)).isEqualTo("OPEN");
    }

    /** It runs only in the transaction of the caller (its person lock), never on its own. */
    @Test
    void theGuardNeedsTheTransactionOfTheCaller() {
        Setup setup = realBot("AUTO_EXITS");

        assertThatThrownBy(() -> guard.releaseForSale(setup.tenantId(), setup.userId(), setup.mint()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
