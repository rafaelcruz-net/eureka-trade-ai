package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.realexecution.domain.solana.WireTransaction;
import com.memetrade.realexits.AbstractRealExitsIT;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 5 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR4.3, U7-NFR4.3, logical-components.md "Venda
 * pedida pela pessoa"): a market sale the person signs in the co-signed modes, while a conditional holds the same
 * position, answers {@code 409 EXIT_CANCELLING} ("Cancelando a saída automática; tente em instantes") and marks the
 * conditional CANCELLING; when the conditional already sold the position, {@code 409 NOTHING_TO_SELL}. Nothing reaches
 * the TxSigner in either case.
 */
@Tag("u7-exits-and-fiscal")
class PersonSaleExitIT extends AbstractRealExitsIT {

    @Autowired
    private SigningSessionService sessions;

    private static Principal person(Setup setup) {
        return new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER);
    }

    /** A sale of the bot in AUTO_EXITS (co-signed like SIGNED), quoted by the driver: its session is open. */
    private UUID openSale(Setup setup) {
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID request = transactions
                .execute(status -> decisionStep.submit(RealDecisionStep.Candidate.sell(
                        setup.tenantId(),
                        setup.userId(),
                        setup.botId(),
                        UUID.randomUUID(),
                        setup.mint(),
                        5_000L,
                        "alvo")))
                .decisionRequestId()
                .orElseThrow();
        consumer.runOnce();
        driver.runOnce();
        UUID order = orderOf(request);
        assertThat(state(order)).isEqualTo("QUOTED");
        return order;
    }

    private String signed(Setup setup, UUID order) {
        WireTransaction unsigned = WireTransaction.decodeBase64(
                sessions.session(person(setup), order).transactionBase64());
        return unsigned.withSignature(0, setup.main().signBytes(unsigned.messageBytes()))
                .toBase64();
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException refused) {
            return refused.code();
        }
        throw new AssertionError("the call was not refused");
    }

    @Test
    void theSignatureOfASaleWhileAConditionalHoldsThePositionAnswersExitCancelling() {
        Setup setup = realBot("AUTO_EXITS");
        UUID order = openSale(setup);
        String signedSale = signed(setup, order);
        UUID conditional = conditional(setup, "OPEN", 5_000L);

        assertThat(codeOf(() -> sessions.submit(person(setup), order, signedSale)))
                .isEqualTo(ErrorCode.EXIT_CANCELLING);
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLING");
        assertThat(state(order)).as("the session stays open until its deadline").isEqualTo("QUOTED");
        assertThat(signer.swapCalls.get()).isZero();
    }

    @Test
    void theSignatureOfASaleOfAPositionTheConditionalAlreadySoldAnswersNothingToSell() {
        Setup setup = realBot("AUTO_EXITS");
        UUID order = openSale(setup);
        String signedSale = signed(setup, order);
        String fill = "conditional:" + UUID.randomUUID();
        // 261121-paper-real-unico U2 (BR1.1): the fill of the conditional comes after the buy in the network order.
        clock.advance(java.time.Duration.ofSeconds(1));
        transactions.executeWithoutResult(status -> ledger.record(RealEntry.swapFill(
                setup.tenantId(),
                setup.botWalletId(),
                RealEntryKind.SWAP_FILL_OUT,
                1_200_000L,
                setup.mint(),
                5_000L,
                fill,
                fill + ":fill")));

        assertThat(codeOf(() -> sessions.submit(person(setup), order, signedSale)))
                .isEqualTo(ErrorCode.NOTHING_TO_SELL);
        assertThat(signer.swapCalls.get()).isZero();
    }
}
