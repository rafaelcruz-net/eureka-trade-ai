package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.FakeCrashPoints;
import com.memetrade.realexecution.domain.solana.WireTransaction;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 9 of the U4, written BEFORE the code (F4, BR4.2, U4-NFR6.2; S6 to S10, S25, S29): the signing session of the
 * SIGNED mode. The server shows the message it built; the browser only signs it; the server checks the deadline, the
 * bytes of the message and the Ed25519 of the main wallet, claims the order with a compare-and-set against the deadline
 * job, rechecks the wallet and asks the TxSigner for the second signature. A claim stuck for 15 s is repeated with the
 * same request, or fails when the quote is older than 10 s.
 */
@Tag("u4-real-execution")
class SigningSessionIT extends AbstractRealExecutionIT {

    @Autowired
    private SigningSessionService sessions;

    private static Principal person(Setup setup) {
        return new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER);
    }

    /** A buy of the SIGNED mode, quoted by the driver: its session is open. */
    private UUID openSession(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        driver.runOnce();
        UUID order = orderOf(request);
        assertThat(state(order)).isEqualTo("QUOTED");
        return order;
    }

    /** What the browser sends back: the transaction of the session with the signature of the main wallet. */
    private static String signedByMain(SigningSessionService.Session session, TestWallet main) {
        WireTransaction unsigned = WireTransaction.decodeBase64(session.transactionBase64());
        return unsigned.withSignature(0, main.signBytes(unsigned.messageBytes()))
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
    void s6TheSessionShowsTheMessageItsDigestTheDeadlineAndWhatTheBotWantsToDo() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);

        SigningSessionService.Session session = sessions.session(person(setup), order);

        assertThat(WireTransaction.decodeBase64(session.transactionBase64()).signatures())
                .hasSize(2);
        assertThat(session.messageDigest()).hasSize(64);
        assertThat(session.deadline()).isNotNull();
        assertThat(session.display().side()).isEqualTo("BUY");
        assertThat(session.display().inputBaseUnits()).isEqualTo(50_000_000L);
        assertThat(session.display().expectedOutBaseUnits()).isEqualTo(1_000_000L);
        assertThat(session.display().minOutBaseUnits()).isEqualTo(980_000L);
        assertThat(session.display().feeLamports()).isEqualTo(13_457L);
        assertThat(session.display().reason()).isEqualTo("sinal de compra");
    }

    @Test
    void s7ASignatureOfTheMainWalletWithinTheWindowIsCosignedSentAndFollowed() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        SigningSessionService.Session session = sessions.session(person(setup), order);
        clock.advance(Duration.ofSeconds(5));

        sessions.submit(person(setup), order, signedByMain(session, setup.main()));

        assertThat(state(order)).isEqualTo("SIGNED");
        driver.runOnce();
        assertThat(state(order)).isEqualTo("SENT");
        assertThat(chain.sends(txSignature(order))).isEqualTo(1);
        assertThat(signer.swapSignatures.get()).isEqualTo(1);
    }

    @Test
    void theSameSignedTransactionSentAgainAnswersTheSameOrderWithoutASecondSignature() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String signed = signedByMain(sessions.session(person(setup), order), setup.main());

        sessions.submit(person(setup), order, signed);
        sessions.submit(person(setup), order, signed);

        assertThat(state(order)).isEqualTo("SIGNED");
        assertThat(signer.swapCalls.get()).isEqualTo(1);
    }

    @Test
    void s8ASignatureAfterTheDeadlineIsRefusedAndTheDeadlineJobCancelsTheOrder() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String signed = signedByMain(sessions.session(person(setup), order), setup.main());
        clock.advance(Duration.ofSeconds(8));

        assertThat(codeOf(() -> sessions.submit(person(setup), order, signed)))
                .isEqualTo(ErrorCode.SIGNING_DEADLINE_EXPIRED);
        deadlines.runOnce();

        assertThat(state(order)).isEqualTo("CANCELLED");
        assertThat(failureCode(order)).isEqualTo("SIGNING_DEADLINE_EXPIRED");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
        assertThat(chain.totalSends()).isZero();
    }

    @Test
    void s10ATransactionWithAnotherMessageIsRefusedAndTheOrderStaysQuoted() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        SigningSessionService.Session session = sessions.session(person(setup), order);
        WireTransaction unsigned = WireTransaction.decodeBase64(session.transactionBase64());
        byte[] changed = unsigned.messageBytes();
        changed[changed.length - 2] ^= 0x01;
        String other = WireTransaction.decode(serialize(2, setup.main().signBytes(changed), changed))
                .toBase64();

        assertThat(codeOf(() -> sessions.submit(person(setup), order, other)))
                .isEqualTo(ErrorCode.SIGNED_MESSAGE_MISMATCH);
        assertThat(state(order)).isEqualTo("QUOTED");
        assertThat(signer.swapCalls.get()).isZero();
    }

    @Test
    void aSignatureOfAnotherWalletOverTheSameMessageIsRefused() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String forged = signedByMain(sessions.session(person(setup), order), TestWallet.generate());

        assertThat(codeOf(() -> sessions.submit(person(setup), order, forged)))
                .isEqualTo(ErrorCode.SIGNED_MESSAGE_MISMATCH);
        assertThat(state(order)).isEqualTo("QUOTED");
    }

    @Test
    void s9ARefusalInTheWalletCancelsTheOrderUserRejected() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);

        sessions.reject(person(setup), order);

        assertThat(state(order)).isEqualTo("CANCELLED");
        assertThat(failureCode(order)).isEqualTo("USER_REJECTED");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
    }

    @Test
    void s25AClaimedSessionIsNeverCancelledByTheDeadlineJob() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String signed = signedByMain(sessions.session(person(setup), order), setup.main());
        crashes.crashAt("claimed");
        clock.advance(Duration.ofSeconds(7));

        assertThatThrownBy(() -> sessions.submit(person(setup), order, signed))
                .isInstanceOf(FakeCrashPoints.SimulatedCrash.class);
        clock.advance(Duration.ofSeconds(2));
        deadlines.runOnce();

        assertThat(state(order)).as("claimed before the deadline").isEqualTo("QUOTED");
        assertThat(failureCode(order)).isNull();
    }

    @Test
    void s29AClaimStuckFifteenSecondsWithAFreshQuoteIsRepeatedWithTheSameRequest() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String signed = signedByMain(sessions.session(person(setup), order), setup.main());
        signer.down = true;

        assertThat(codeOf(() -> sessions.submit(person(setup), order, signed)))
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(state(order)).isEqualTo("QUOTED");
        signer.down = false;
        jdbc.sql("update real_order set quote_at = quote_at + interval '20 seconds' where real_order_id = :id")
                .param("id", order)
                .update();
        clock.advance(Duration.ofSeconds(15));
        deadlines.runOnce();

        assertThat(state(order)).isEqualTo("SIGNED");
    }

    @Test
    void s29AClaimStuckWithAQuoteOlderThanTenSecondsFailsQuoteExpiredAndSendsNothing() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String signed = signedByMain(sessions.session(person(setup), order), setup.main());
        crashes.crashAt("claimed");

        assertThatThrownBy(() -> sessions.submit(person(setup), order, signed))
                .isInstanceOf(FakeCrashPoints.SimulatedCrash.class);
        clock.advance(Duration.ofSeconds(15));
        deadlines.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("QUOTE_EXPIRED");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
        assertThat(chain.totalSends()).isZero();
    }

    @Test
    void theSessionOfAnotherPersonOrOfAnAutoFullOrderIsNotFound() {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        Setup other = realBot("AUTO_FULL");
        UUID request = pendingBuy(other, 10_000_000L);

        assertThat(codeOf(() -> sessions.session(person(other), order))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> sessions.reject(person(other), order))).isEqualTo(ErrorCode.NOT_FOUND);
        consumer.runOnce();
        UUID auto = orderOf(request);
        assertThat(codeOf(() -> sessions.session(person(other), auto))).isEqualTo(ErrorCode.NOT_FOUND);
    }

    private static byte[] serialize(int count, byte[] firstSignature, byte[] message) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(count);
        out.writeBytes(firstSignature);
        for (int i = 1; i < count; i++) {
            out.writeBytes(new byte[64]);
        }
        out.writeBytes(message);
        return out.toByteArray();
    }
}
