package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.RealBalance;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 7 of the U5, written BEFORE the code (BR2.4, S15, U5-NFR4.5, project.md "saque sempre disponível"): with a
 * divergence OPEN or COMPENSATED of the wallet, or the restore pause, the withdrawable amount is the SMALLER of the free
 * balance of the ledger and the SOL measured on the network by the last reconciliation, minus the fee reserve; the total
 * withdrawal uses that smaller amount. Without them, the rule of the U1 does not change. The withdrawal never becomes
 * unavailable: a value above the limit is refused with WITHDRAWAL_LIMITED_BY_DIVERGENCE and its maximum.
 */
@Tag("u5-real-operations")
class WithdrawalLimitTest {

    private static final long SOL = 1_000_000_000L;
    private static final long RESERVE = 30_000_000L;
    private static final long FEE = 5_000L;
    private static final WithdrawalLimits LIMITS =
            new WithdrawalLimits(RESERVE, FEE, 890_880L, Optional.empty(), false);

    private static RealBalance ledger(long free) {
        return new RealBalance(free, 0, free, Set.of(), false);
    }

    private static WithdrawalLimit.Context divergence(long chainLamports) {
        return new WithdrawalLimit.Context(true, false, Optional.of(chainLamports));
    }

    private static final WithdrawalLimit.Context NONE = new WithdrawalLimit.Context(false, false, Optional.empty());

    /** S15: ledger 2 SOL, network 1.5 SOL: the total withdraws 1.5 SOL minus the reserve (and its own fee). */
    @Test
    void withADivergenceTheTotalUsesTheSmallerBalanceMinusTheReserve() {
        WithdrawalAmount total = WithdrawalLimit.total(ledger(2 * SOL), LIMITS, divergence(3 * SOL / 2));

        assertThat(total.kind()).isEqualTo(WithdrawalKind.TOTAL);
        assertThat(total.lamports()).isEqualTo(3 * SOL / 2 - RESERVE - FEE);
    }

    @Test
    void withoutDivergenceNorRestoreTheRuleOfTheU1DoesNotChange() {
        assertThat(WithdrawalLimit.total(ledger(2 * SOL), LIMITS, NONE))
                .isEqualTo(WithdrawalCalculator.total(ledger(2 * SOL), LIMITS));
        assertThat(WithdrawalLimit.partial(ledger(2 * SOL), SOL, LIMITS, NONE))
                .isEqualTo(WithdrawalCalculator.partial(ledger(2 * SOL), SOL, LIMITS));
    }

    @Test
    void aPartialWithinTheLimitPasses() {
        WithdrawalAmount partial = WithdrawalLimit.partial(ledger(2 * SOL), SOL, LIMITS, divergence(3 * SOL / 2));

        assertThat(partial.lamports()).isEqualTo(SOL);
    }

    @Test
    void aPartialAboveTheLimitButWithinTheLedgerIsRefusedLimitedByDivergenceWithTheMaximum() {
        assertThatThrownBy(
                        () -> WithdrawalLimit.partial(ledger(2 * SOL), 18 * SOL / 10, LIMITS, divergence(3 * SOL / 2)))
                .isInstanceOfSatisfying(BotWalletException.class, refused -> {
                    assertThat(refused.error()).isEqualTo(BotWalletError.WITHDRAWAL_LIMITED_BY_DIVERGENCE);
                    assertThat(refused.maxWithdrawableLamports()).isEqualTo(3 * SOL / 2 - RESERVE - FEE);
                });
    }

    @Test
    void aPartialAboveTheLedgerItselfKeepsTheRefusalOfTheU1() {
        assertThatThrownBy(() -> WithdrawalLimit.partial(ledger(SOL), 2 * SOL, LIMITS, divergence(3 * SOL)))
                .isInstanceOfSatisfying(BotWalletException.class, refused -> assertThat(refused.error())
                        .isEqualTo(BotWalletError.INSUFFICIENT_FREE_BALANCE));
    }

    @Test
    void whenTheNetworkHasMoreThanTheLedgerTheLedgerLimits() {
        WithdrawalAmount total = WithdrawalLimit.total(ledger(SOL), LIMITS, divergence(5 * SOL));

        assertThat(total.lamports()).isEqualTo(SOL - RESERVE - FEE);
    }

    @Test
    void theRestorePauseAlsoLimits() {
        WithdrawalLimit.Context restore = new WithdrawalLimit.Context(false, true, Optional.of(SOL / 2));

        assertThat(WithdrawalLimit.total(ledger(2 * SOL), LIMITS, restore).lamports())
                .isEqualTo(SOL / 2 - RESERVE - FEE);
    }

    /** U5-NFR4.5: without any measure of the network the withdrawal is never blocked; the ledger decides. */
    @Test
    void withoutAMeasureOfTheNetworkTheLedgerDecides() {
        WithdrawalLimit.Context unmeasured = new WithdrawalLimit.Context(true, false, Optional.empty());

        assertThat(WithdrawalLimit.total(ledger(2 * SOL), LIMITS, unmeasured))
                .isEqualTo(WithdrawalCalculator.total(ledger(2 * SOL), LIMITS));
    }

    @Test
    void theReservedAmountsLeaveTheNetworkBalanceToo() {
        RealBalance reserved = new RealBalance(2 * SOL, SOL / 2, 3 * SOL / 2, Set.of("order:x"), false);

        WithdrawalAmount total = WithdrawalLimit.total(reserved, LIMITS, divergence(SOL));

        assertThat(total.lamports()).isEqualTo(SOL / 2 - RESERVE - FEE);
    }

    @Test
    void anAmbiguousOperationStillBlocksTheTotalAsInTheU1() {
        WithdrawalLimits ambiguous = new WithdrawalLimits(RESERVE, FEE, 890_880L, Optional.empty(), true);

        assertThatThrownBy(() -> WithdrawalLimit.total(ledger(2 * SOL), ambiguous, divergence(SOL)))
                .isInstanceOfSatisfying(BotWalletException.class, refused -> assertThat(refused.error())
                        .isEqualTo(BotWalletError.ORDER_AMBIGUOUS));
    }

    @Test
    void theLimitAppliesOnlyWithADivergenceOrARestore() {
        assertThat(WithdrawalLimit.networkBalance(NONE)).isEmpty();
        assertThat(WithdrawalLimit.networkBalance(divergence(7))).contains(7L);
        assertThat(WithdrawalLimit.networkBalance(new WithdrawalLimit.Context(false, false, Optional.of(7L))))
                .isEmpty();
    }
}
