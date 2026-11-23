package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import com.memetrade.riskcontrol.real.domain.BuyInputs;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.LamportPrice;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 6 of the U3, written before the code (BR6.3, BR4.3, BR5.1, BR5.2, U3-NFR2.1, U3-NFR6.1): the inputs of a buy come
 * from the REAL ledger of the wallet of this tenant only, the prices of the positions in lamports per base unit, the
 * flows from after the photo of the day only (S4b), and the open reservations and the count of the barrier.
 */
class RealGateInputsReaderIT extends AbstractRealRiskIT {

    @Autowired
    private RealGateInputsReader reader;

    @Autowired
    private JdbcRealRiskStore store;

    @Test
    void theStateOfTheWalletIsReadOnlyInItsTenant() {
        Wallet wallet = releasedWallet(10 * SOL);

        RealStateView state = reader.wallet(wallet.tenantId(), wallet.userId()).orElseThrow();

        assertThat(state.botWalletId()).isEqualTo(wallet.botWalletId());
        assertThat(state.released()).isTrue();
        assertThat(reader.wallet(UUID.randomUUID(), wallet.userId())).isEmpty();
    }

    @Test
    void theBuyReadsTheRealLedgerThePositionsInLamportsAndTheSignalsOfTheToken() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 5 * SOL);
        orderReserve(wallet, SOL);
        solAt("200", 5);
        String held = token("0.0002", 6, 5);
        position(wallet, held, 1_000_000_000L, 2 * SOL);
        String target = token("0.01", 6, 3);
        OrderIntent intent = OrderIntent.botBuy(
                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), target, SOL / 2);
        RealStateView state = reader.wallet(wallet.tenantId(), wallet.userId()).orElseThrow();

        BuyInputs inputs = reader.buy(intent, state, clock.instant());

        assertThat(inputs.balance().totalLamports()).isEqualTo(3 * SOL);
        assertThat(inputs.balance().orderReservedLamports()).isEqualTo(SOL);
        assertThat(inputs.positions()).singleElement().satisfies(position -> {
            assertThat(position.mint()).isEqualTo(held);
            assertThat(position.tokenBaseUnits()).isEqualTo(1_000_000_000L);
            // 261121-paper-real-unico U2 (R-13, F-3): the buy never reads the cost.
            assertThat(position.cost())
                    .isEqualTo(new com.memetrade.ledger.api.RealCost.Unknown(
                            com.memetrade.ledger.api.RealCostUnknownReason.NOT_READ));
            // 0.0002 USD / 200 USD x 10^9 / 10^6 = 0.001 lamport per base unit.
            assertThat(position.price())
                    .isInstanceOfSatisfying(LamportPrice.Known.class, price -> assertThat(price.lamportsPerBaseUnit())
                            .isEqualByComparingTo("0.001"));
        });
        assertThat(inputs.price().isKnown()).isTrue();
        assertThat(inputs.liquidity().isKnown()).isTrue();
        assertThat(inputs.volume24h().isKnown()).isTrue();
        assertThat(inputs.blocked()).isFalse();
        assertThat(inputs.approvedBuysToday()).isZero();
        assertThat(inputs.snapshot()).isEmpty();
    }

    @Test
    void aPositionWithoutAQuoteIsUnknownNeverZero() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 5 * SOL);
        position(wallet, SyntheticIds.mint(), 1_000, SOL);
        String target = token("0.01", 6, 3);
        OrderIntent intent = OrderIntent.botBuy(
                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), target, SOL / 2);

        BuyInputs inputs = reader.buy(
                intent, reader.wallet(wallet.tenantId(), wallet.userId()).orElseThrow(), clock.instant());

        assertThat(inputs.positions()).singleElement().satisfies(position -> assertThat(position.price())
                .isInstanceOf(LamportPrice.Unknown.class));
    }

    /** S4b: the deposit of 08:00 is inside the photo of 09:00; only the deposit after the photo is a flow. */
    @Test
    void theFlowsComeFromAfterThePhotoOnly() {
        Wallet wallet = releasedWallet(10 * SOL);
        clock.set(Instant.parse("2026-11-14T08:00:00Z"));
        deposit(wallet, 2 * SOL);
        clock.set(Instant.parse("2026-11-14T09:00:00Z"));
        LocalDate day = LocalDate.of(2026, 11, 14);
        transactions.executeWithoutResult(status -> store.insertSnapshot(new DaySnapshot(
                wallet.tenantId(),
                wallet.botWalletId(),
                day,
                2 * SOL,
                10 * SOL,
                clock.instant(),
                DaySnapshot.TakenBy.DAY_JOB)));
        clock.advance(Duration.ofHours(1));
        deposit(wallet, 3 * SOL);
        String target = token("0.01", 6, 3);
        OrderIntent intent = OrderIntent.botBuy(
                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), target, SOL / 2);

        BuyInputs inputs = reader.buy(
                intent, reader.wallet(wallet.tenantId(), wallet.userId()).orElseThrow(), clock.instant());

        assertThat(inputs.snapshot().orElseThrow().startValueLamports()).isEqualTo(2 * SOL);
        assertThat(inputs.flowsSinceSnapshot().depositedLamports()).isEqualTo(3 * SOL);
        assertThat(inputs.flowsSinceSnapshot().withdrawnLamports()).isZero();
    }

    /**
     * Review R-02, on real PostgreSQL: a SQL error in a read of the barrier aborts the transaction of the caller.
     * {@code RealGateUnavailable.transactionUsable()} says so, and the next statement of the caller indeed fails: the
     * U4 has to roll back. Only the lock timeout leaves the transaction usable (WalletLockIT).
     */
    @Test
    void aSqlFailureInAReadAbortsTheTransactionOfTheCallerAndTheFailureSaysSo() {
        com.memetrade.ledger.api.RealLedger broken =
                org.mockito.Mockito.mock(com.memetrade.ledger.api.RealLedger.class);
        org.mockito.Mockito.when(broken.view(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call -> jdbc.sql("select 1 / 0").query(Integer.class).single());
        RealGateInputsReader failing = new RealGateInputsReader(
                org.mockito.Mockito.mock(com.memetrade.botwallet.api.RealStateQuery.class),
                broken,
                market,
                assessment,
                store);
        UUID tenant = UUID.randomUUID();

        String afterwards = transactions.execute(status -> {
            com.memetrade.riskcontrol.api.RealGateUnavailable failure =
                    org.assertj.core.api.Assertions.catchThrowableOfType(
                            com.memetrade.riskcontrol.api.RealGateUnavailable.class,
                            () -> failing.value(
                                    tenant, UUID.randomUUID(), UUID.randomUUID(), java.util.Optional.empty(), false));
            assertThat(failure.dependency())
                    .isEqualTo(com.memetrade.riskcontrol.api.RealGateUnavailable.Dependency.LEDGER);
            assertThat(failure.transactionUsable()).isFalse();
            Throwable next = org.assertj.core.api.Assertions.catchThrowable(
                    () -> jdbc.sql("select 1").query(Integer.class).single());
            status.setRollbackOnly();
            return com.memetrade.testsupport.LedgerRows.sqlState(next);
        });

        assertThat(afterwards).as("in failed SQL transaction").isEqualTo("25P02");
    }

    /** S7, U3-NFR2.1: a PAPER balance never reaches the barrier; the REAL ledger of the wallet is empty. */
    @Test
    void thePaperLedgerIsNeverRead() {
        Wallet wallet = releasedWallet(10 * SOL);
        String target = token("0.01", 6, 3);
        OrderIntent intent = OrderIntent.botBuy(
                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), target, SOL / 2);

        BuyInputs inputs = reader.buy(
                intent, reader.wallet(wallet.tenantId(), wallet.userId()).orElseThrow(), clock.instant());

        assertThat(inputs.balance().totalLamports()).isZero();
        assertThat(inputs.balance().freeLamports()).isZero();
    }

    @Test
    void anEntryOfAnotherWalletNeverEntersTheBalance() {
        Wallet wallet = releasedWallet(10 * SOL);
        Wallet other = releasedWallet(10 * SOL);
        transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                other.tenantId(),
                other.botWalletId(),
                RealEntryKind.DEPOSIT,
                7 * SOL,
                null,
                "dep:o",
                "dep:o:" + UUID.randomUUID())));
        String target = token("0.01", 6, 3);
        OrderIntent intent = OrderIntent.botBuy(
                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), target, SOL / 2);

        assertThat(reader.buy(
                                intent,
                                reader.wallet(wallet.tenantId(), wallet.userId())
                                        .orElseThrow(),
                                clock.instant())
                        .balance()
                        .totalLamports())
                .isZero();
    }
}
