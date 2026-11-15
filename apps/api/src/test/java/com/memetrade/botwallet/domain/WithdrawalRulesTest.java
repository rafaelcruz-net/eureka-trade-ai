package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealEntryKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 16, written before the code (BR4.3 to BR4.7, NFR4.2 to NFR4.4, NFR4.10, AC5.1.1 to AC5.1.4): what can be
 * withdrawn, the total withdrawal that empties the wallet, the rent-exempt minimum, the divergence limit, the fee known
 * before signing, the states of a withdrawal and its ledger entries, and FAILED only when both providers agree.
 */
class WithdrawalRulesTest {

    private static final long SOL = 1_000_000_000L;
    private static final long FEE_RESERVE = 30_000_000L;
    private static final long FEE = 5_000L;
    private static final long RENT_EXEMPT = 890_880L;
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private static RealBalance free(long lamports) {
        return new RealBalance(lamports, 0, lamports, Set.of(), false);
    }

    private static WithdrawalLimits limits(Optional<Long> networkBalance, boolean ambiguous) {
        return new WithdrawalLimits(FEE_RESERVE, FEE, RENT_EXEMPT, networkBalance, ambiguous);
    }

    private static BotWalletError errorOf(Runnable action) {
        try {
            action.run();
        } catch (BotWalletException refused) {
            return refused.error();
        }
        throw new AssertionError("accepted");
    }

    @Test
    void aPartialWithdrawalKeepsTheFeeReserve() {
        WithdrawalAmount amount =
                WithdrawalCalculator.partial(free(SOL), SOL - FEE_RESERVE - FEE, limits(Optional.empty(), false));

        assertThat(amount.lamports()).isEqualTo(SOL - FEE_RESERVE - FEE);
        assertThat(amount.reservedLamports()).isEqualTo(SOL - FEE_RESERVE);
        assertThat(errorOf(() -> WithdrawalCalculator.partial(
                        free(SOL), SOL - FEE_RESERVE - FEE + 1, limits(Optional.empty(), false))))
                .isEqualTo(BotWalletError.INSUFFICIENT_FREE_BALANCE);
    }

    @Test
    void theRefusalNamesTheMaximumWithdrawable() {
        try {
            WithdrawalCalculator.partial(free(SOL), 2 * SOL, limits(Optional.empty(), false));
        } catch (BotWalletException refused) {
            assertThat(refused.maxWithdrawableLamports()).isEqualTo(SOL - FEE_RESERVE - FEE);
            return;
        }
        throw new AssertionError("accepted");
    }

    @Test
    void aPartialWithdrawalNeverLeavesTheWalletBetweenZeroAndTheRentExemptMinimum() {
        // 20 lamports leave 890_870 behind: above zero and below the rent-exempt minimum of 890_880.
        RealBalance balance = free(RENT_EXEMPT + FEE + 10L);

        assertThat(errorOf(() -> WithdrawalCalculator.partial(balance, 20L, limitsWithoutReserve())))
                .isEqualTo(BotWalletError.INSUFFICIENT_FREE_BALANCE);
        assertThat(WithdrawalCalculator.partial(balance, RENT_EXEMPT + 10L, limitsWithoutReserve())
                        .lamports())
                .isEqualTo(RENT_EXEMPT + 10L);
    }

    private static WithdrawalLimits limitsWithoutReserve() {
        return new WithdrawalLimits(0, FEE, RENT_EXEMPT, Optional.empty(), false);
    }

    @Test
    void theTotalWithdrawalEmptiesTheWalletIncludingTheReserve() {
        WithdrawalAmount amount = WithdrawalCalculator.total(free(SOL), limits(Optional.empty(), false));

        assertThat(amount.kind()).isEqualTo(WithdrawalKind.TOTAL);
        assertThat(amount.lamports()).isEqualTo(SOL - FEE);
        assertThat(amount.reservedLamports()).isEqualTo(SOL);
    }

    @Test
    void theTotalWithdrawalWithAnOrderInProgressKeepsTheReserveAndWithAnAmbiguousOneIsRefused() {
        RealBalance withOrder = new RealBalance(SOL, 0, SOL, Set.of(), true);

        assertThat(WithdrawalCalculator.total(withOrder, limits(Optional.empty(), false))
                        .lamports())
                .isEqualTo(SOL - FEE_RESERVE - FEE);
        assertThat(errorOf(() -> WithdrawalCalculator.total(free(SOL), limits(Optional.empty(), true))))
                .isEqualTo(BotWalletError.ORDER_AMBIGUOUS);
    }

    @Test
    void withADivergenceTheWithdrawableUsesTheSmallerOfLedgerAndNetwork() {
        WithdrawalAmount amount = WithdrawalCalculator.total(free(2 * SOL), limits(Optional.of(SOL), false));

        assertThat(amount.lamports()).isEqualTo(SOL - FEE);
    }

    @Test
    void theFeeIsKnownBeforeSigning() {
        assertThat(WithdrawalFees.calculate(5_000L, 450, 0L)).isEqualTo(5_000L);
        assertThat(WithdrawalFees.calculate(5_000L, 450, 2L)).isEqualTo(5_001L);
        assertThat(WithdrawalFees.calculate(5_000L, 1_000, 100_000L)).isEqualTo(5_100L);
    }

    @Test
    void aConfirmedWithdrawalWritesTheOutputTheRealFeeAndReleasesTheReserve() {
        Withdrawal sent = requested(SOL)
                .signed("dGVzdA==", "aa".repeat(32), "hash", 100L, NOW)
                .sent(NOW);

        WithdrawalTransition confirmed = sent.confirmed("Sig1", 4_900L, NOW);

        assertThat(confirmed.withdrawal().state()).isEqualTo(WithdrawalState.CONFIRMED);
        assertThat(confirmed.entries())
                .extracting(e -> e.kind() + ":" + e.lamports() + ":" + e.idempotencyKey())
                .containsExactly(
                        "WITHDRAWAL:-" + SOL + ":wd:" + sent.withdrawalId() + ":out",
                        "NETWORK_FEE:-4900:wd:" + sent.withdrawalId() + ":fee",
                        "RESERVE_RELEASE:" + (SOL + FEE) + ":wd:" + sent.withdrawalId() + ":release");
    }

    @Test
    void aRefusalOfTheTxSignerFailsAndOnlyReleasesTheReserve() {
        Withdrawal requested = requested(SOL);

        WithdrawalTransition failed = requested.refusedBySigner("DESTINATION_NOT_ALLOWED", NOW);

        assertThat(failed.withdrawal().state()).isEqualTo(WithdrawalState.FAILED);
        assertThat(failed.withdrawal().failureCode()).isEqualTo("DESTINATION_NOT_ALLOWED");
        assertThat(failed.entries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(RealEntryKind.RESERVE_RELEASE);
            assertThat(e.lamports()).isEqualTo(SOL + FEE);
        });
    }

    @Test
    void sixtySecondsWithoutAResultMakeItAmbiguous() {
        Withdrawal sent = requested(SOL)
                .signed("dGVzdA==", "aa".repeat(32), "hash", 100L, NOW)
                .sent(NOW);

        assertThat(sent.withoutResult(NOW.plusSeconds(59)).state()).isEqualTo(WithdrawalState.SENT);
        assertThat(sent.withoutResult(NOW.plusSeconds(60)).state()).isEqualTo(WithdrawalState.AMBIGUOUS);
    }

    @Test
    void anAmbiguousWithdrawalFailsOnlyWhenBothProvidersAgreeTheBlockhashExpired() {
        Withdrawal ambiguous = requested(SOL)
                .signed("dGVzdA==", "aa".repeat(32), "hash", 100L, NOW)
                .sent(NOW)
                .withoutResult(NOW.plusSeconds(60));
        ChainObservation expired = ChainObservation.notFound(101L);

        assertThat(ambiguous
                        .afterChainCheck(expired, ChainObservation.unavailable(), NOW)
                        .withdrawal()
                        .state())
                .isEqualTo(WithdrawalState.AMBIGUOUS);
        assertThat(ambiguous
                        .afterChainCheck(expired, ChainObservation.notFound(99L), NOW)
                        .withdrawal()
                        .state())
                .isEqualTo(WithdrawalState.AMBIGUOUS);
        assertThat(ambiguous.afterChainCheck(expired, expired, NOW).withdrawal().state())
                .isEqualTo(WithdrawalState.FAILED);
        assertThat(ambiguous
                        .afterChainCheck(ChainObservation.finalized("Sig1", 5_000L), expired, NOW)
                        .withdrawal()
                        .state())
                .isEqualTo(WithdrawalState.CONFIRMED);
    }

    @Test
    void aSignedWithdrawalNeverAsksForASecondSignature() {
        Withdrawal signed = requested(SOL).signed("dGVzdA==", "aa".repeat(32), "hash", 100L, NOW);

        assertThatThrownBy(() -> signed.signed("b3RoZXI=", "bb".repeat(32), "hash2", 200L, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(signed.needsSignature()).isFalse();
        assertThat(requested(SOL).needsSignature()).isTrue();
    }

    @Test
    void anUnsignedWithdrawalExpiresAfter24HoursAndRetriesWithAGrowingWait() {
        Withdrawal requested = requested(SOL);

        assertThat(requested
                        .expireIfUnsigned(NOW.plus(Duration.ofHours(23)))
                        .withdrawal()
                        .state())
                .isEqualTo(WithdrawalState.REQUESTED);
        assertThat(requested
                        .expireIfUnsigned(NOW.plus(Duration.ofHours(24)))
                        .withdrawal()
                        .state())
                .isEqualTo(WithdrawalState.FAILED);
        Withdrawal retried = requested.retryLater(NOW).retryLater(NOW);
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(retried.nextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        Withdrawal many = requested;
        for (int i = 0; i < 20; i++) {
            many = many.retryLater(NOW);
        }
        assertThat(many.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void theRequestReservesTheAmountPlusTheFee() {
        WithdrawalTransition requested = Withdrawal.request(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new WithdrawalAmount(WithdrawalKind.PARTIAL, SOL, FEE),
                UUID.randomUUID(),
                NOW);

        assertThat(requested.entries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(RealEntryKind.RESERVE);
            assertThat(e.lamports()).isEqualTo(SOL + FEE);
            assertThat(e.idempotencyKey()).endsWith(":reserve");
        });
    }

    private static Withdrawal requested(long lamports) {
        return Withdrawal.request(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new WithdrawalAmount(WithdrawalKind.PARTIAL, lamports, FEE),
                        UUID.randomUUID(),
                        NOW)
                .withdrawal();
    }
}
