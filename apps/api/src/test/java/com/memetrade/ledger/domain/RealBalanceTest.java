package com.memetrade.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 13, written before the code (BR3.3, BR3.4, BR3.5, AC2.2.2, AC2.2.3): one sign convention, the total only from the
 * balance entries, the reserved amount from RESERVE minus RESERVE_RELEASE, the free balance, the operable part up to the
 * cap and the part above the cap available only to withdraw; open reservations read from the ledger itself.
 */
class RealBalanceTest {

    private static final long SOL = 1_000_000_000L;
    private static final long FEE_RESERVE = 30_000_000L;

    private static RealLedgerRow row(RealEntryKind kind, long lamports, String reference) {
        return new RealLedgerRow(kind, lamports, reference);
    }

    @Test
    void anEmptyWalletHasNothing() {
        RealBalance balance = RealBalances.of(List.of());

        assertThat(balance.totalLamports()).isZero();
        assertThat(balance.freeLamports()).isZero();
        assertThat(balance.operableLamports(2 * SOL, FEE_RESERVE)).isZero();
        assertThat(balance.aboveCapLamports(2 * SOL, FEE_RESERVE)).isZero();
    }

    @Test
    void theTotalSumsOnlyTheBalanceEntriesWithTheirSign() {
        RealBalance balance = RealBalances.of(List.of(
                row(RealEntryKind.DEPOSIT, 3 * SOL, "dep:a"),
                row(RealEntryKind.WITHDRAWAL, -SOL, "wd:1"),
                row(RealEntryKind.NETWORK_FEE, -5_000L, "wd:1"),
                row(RealEntryKind.RESERVE, SOL + 5_000L, "wd:1"),
                row(RealEntryKind.RESERVE_RELEASE, SOL + 5_000L, "wd:1")));

        assertThat(balance.totalLamports()).isEqualTo(2 * SOL - 5_000L);
        assertThat(balance.reservedLamports()).isZero();
        assertThat(balance.freeLamports()).isEqualTo(2 * SOL - 5_000L);
        assertThat(balance.openReservations()).isEmpty();
    }

    @Test
    void anOpenReservationStaysOutOfTheFreeBalance() {
        RealBalance balance = RealBalances.of(
                List.of(row(RealEntryKind.DEPOSIT, 2 * SOL, "dep:a"), row(RealEntryKind.RESERVE, SOL / 2, "wd:2")));

        assertThat(balance.totalLamports()).isEqualTo(2 * SOL);
        assertThat(balance.reservedLamports()).isEqualTo(SOL / 2);
        assertThat(balance.freeLamports()).isEqualTo(2 * SOL - SOL / 2);
        assertThat(balance.openReservations()).containsExactly("wd:2");
    }

    @Test
    void theBotOperatesOnlyUpToTheCapAndTheRestIsOnlyForWithdrawal() {
        RealBalance balance = RealBalances.of(List.of(row(RealEntryKind.DEPOSIT, 5 * SOL, "dep:a")));

        assertThat(balance.operableLamports(2 * SOL, FEE_RESERVE)).isEqualTo(2 * SOL);
        assertThat(balance.aboveCapLamports(2 * SOL, FEE_RESERVE)).isEqualTo(3 * SOL - FEE_RESERVE);
    }

    @Test
    void theFeeReserveIsOutOfTheOperableBalance() {
        RealBalance balance = RealBalances.of(List.of(row(RealEntryKind.DEPOSIT, SOL, "dep:a")));

        assertThat(balance.operableLamports(2 * SOL, FEE_RESERVE)).isEqualTo(SOL - FEE_RESERVE);
        assertThat(balance.aboveCapLamports(2 * SOL, FEE_RESERVE)).isZero();
    }

    @Test
    void aBalanceBelowTheFeeReserveIsNeverNegativeOperable() {
        RealBalance balance = RealBalances.of(List.of(row(RealEntryKind.DEPOSIT, 10_000L, "dep:a")));

        assertThat(balance.operableLamports(SOL, FEE_RESERVE)).isZero();
        assertThat(balance.aboveCapLamports(SOL, FEE_RESERVE)).isZero();
    }

    @Test
    void positionsComeFromTheSwapFillsOfTheLedgerAndAreEmptyInTheU1() {
        RealBalance balance = RealBalances.of(List.of(row(RealEntryKind.DEPOSIT, SOL, "dep:a")));

        assertThat(balance.hasOpenPositionOrOrder()).isFalse();
        assertThat(RealBalances.of(List.of(row(RealEntryKind.SWAP_FILL_OUT, -SOL, "order:1")))
                        .hasOpenPositionOrOrder())
                .isTrue();
    }

    @Test
    void anEntryAgainstTheSignConventionIsRefused() {
        UUID tenant = UUID.randomUUID();
        UUID wallet = UUID.randomUUID();

        assertThatThrownBy(() -> RealEntryRules.validate(
                        new RealEntry(tenant, wallet, RealEntryKind.DEPOSIT, -1L, null, "dep:x", "dep:x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RealEntryRules.validate(
                        new RealEntry(tenant, wallet, RealEntryKind.WITHDRAWAL, 5L, null, "wd:1", "wd:1:out")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RealEntryRules.validate(
                        new RealEntry(tenant, wallet, RealEntryKind.RESERVE, -5L, null, "wd:1", "wd:1:reserve")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RealEntryRules.validate(
                        new RealEntry(tenant, wallet, RealEntryKind.COMPENSATION, 0L, null, "c:1", "c:1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSumThatOverflowsSixtyFourBitsFailsInsteadOfWrapping() {
        List<RealLedgerRow> rows =
                List.of(row(RealEntryKind.DEPOSIT, Long.MAX_VALUE, "dep:a"), row(RealEntryKind.DEPOSIT, 1L, "dep:b"));

        assertThatThrownBy(() -> RealBalances.of(rows)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void theKindsSplitIntoBalanceAndReserveEntries() {
        assertThat(RealEntryKind.DEPOSIT.isBalanceEntry()).isTrue();
        assertThat(RealEntryKind.NETWORK_FEE.isBalanceEntry()).isTrue();
        assertThat(RealEntryKind.RESERVE.isBalanceEntry()).isFalse();
        assertThat(RealEntryKind.RESERVE_RELEASE.isBalanceEntry()).isFalse();
    }
}
