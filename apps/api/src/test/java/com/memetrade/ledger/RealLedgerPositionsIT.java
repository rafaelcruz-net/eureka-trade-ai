package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 3 of the U3, written before the code (M2, Q11, BR4.3, BR5.1, BR5.2, BR6.3): the open positions of a bot wallet
 * read from the swap fills, the deposits and withdrawals after an instant, and the reserved lamports split between
 * orders and withdrawals. Every read filters by tenant; the PAPER ledger is never read.
 */
class RealLedgerPositionsIT extends AbstractPostgresIT {

    private static final long SOL = 1_000_000_000L;

    @Autowired
    private RealLedger ledger;

    private record Wallet(UUID tenantId, UUID botWalletId) {}

    private Wallet wallet() {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        UUID botWalletId = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.walletAddress())
                .param("at", Timestamp.from(clock.instant()))
                .update();
        return new Wallet(person.tenantId(), botWalletId);
    }

    private void record(Wallet wallet, RealEntryKind kind, long lamports, String reference) {
        ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                kind,
                lamports,
                null,
                reference,
                UUID.randomUUID().toString()));
    }

    private void signedFill(Wallet wallet, RealEntryKind kind, long lamports, String mint, long units, String sig) {
        ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                kind,
                lamports,
                sig,
                "order:" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                mint,
                units));
    }

    private void slot(String signature, long slot) {
        jdbc.sql("insert into real_entry_slot (tx_signature, slot, recorded_at) values (:s, :slot, now())")
                .param("s", signature)
                .param("slot", slot)
                .update();
    }

    private void fill(Wallet wallet, RealEntryKind kind, long lamports, String mint, long units) {
        ledger.record(RealEntry.swapFill(
                wallet.tenantId(),
                wallet.botWalletId(),
                kind,
                lamports,
                mint,
                units,
                "order:" + UUID.randomUUID(),
                UUID.randomUUID().toString()));
    }

    /**
     * 261121-paper-real-unico U2, Etapa 8 (written before the code; BR2.5, F-3, NFR1.11): positions carry only the
     * quantity of the quantity-only path, with the cost NOT read ({@code Unknown(NOT_READ)}), never 0.
     */
    @Test
    void thePositionCarriesTheOpenQuantityAndACostNotRead() {
        Wallet wallet = wallet();
        String open = SyntheticIds.mint();
        String closed = SyntheticIds.mint();
        record(wallet, RealEntryKind.DEPOSIT, 10 * SOL, "dep:a");
        // Without a recorded slot the rows follow their instant (BR1.3): each fill one second after the previous.
        fill(wallet, RealEntryKind.SWAP_FILL_IN, -2 * SOL, open, 1_000);
        clock.advance(Duration.ofSeconds(1));
        fill(wallet, RealEntryKind.SWAP_FILL_OUT, SOL, open, 400);
        clock.advance(Duration.ofSeconds(1));
        fill(wallet, RealEntryKind.SWAP_FILL_IN, -SOL, closed, 50);
        clock.advance(Duration.ofSeconds(1));
        fill(wallet, RealEntryKind.SWAP_FILL_OUT, 2 * SOL, closed, 50);

        assertThat(ledger.positions(wallet.tenantId(), wallet.botWalletId()))
                .containsExactly(new RealPosition(open, 600, new RealCost.Unknown(RealCostUnknownReason.NOT_READ)));
    }

    /**
     * 261121-paper-real-unico U2, Etapa 8 (BR2.5, BR1.1): the quantity follows the network order, not the recording
     * order: a sale written before the buy in the same slot, but with the bigger signature, sells what the buy brought.
     * The orders of the references do not exist: the quantity never reads C13 nor C8 (NFR1.11).
     */
    @Test
    void theQuantityFollowsTheNetworkOrderAndNeedsNoOrderNorBot() {
        Wallet wallet = wallet();
        String mint = SyntheticIds.mint();
        String[] signatures = {SyntheticIds.signature(), SyntheticIds.signature()};
        java.util.Arrays.sort(
                signatures,
                (a, b) -> java.util.Arrays.compareUnsigned(
                        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        signedFill(wallet, RealEntryKind.SWAP_FILL_OUT, SOL, mint, 100, signatures[1]);
        signedFill(wallet, RealEntryKind.SWAP_FILL_IN, -2 * SOL, mint, 300, signatures[0]);
        slot(signatures[1], 50L);
        slot(signatures[0], 50L);

        assertThat(ledger.positions(wallet.tenantId(), wallet.botWalletId()))
                .containsExactly(new RealPosition(mint, 200, new RealCost.Unknown(RealCostUnknownReason.NOT_READ)));
        assertThat(ledger.view(wallet.tenantId(), wallet.botWalletId(), java.util.Optional.empty())
                        .positions())
                .containsExactly(new RealPosition(mint, 200, new RealCost.Unknown(RealCostUnknownReason.NOT_READ)));
    }

    /**
     * 261121-paper-real-unico U2, Etapa 8 (performance-design.md "Consistência com o view"): the view counts every row
     * of the wallet, of every kind, with the same filter as the selection of the read in network order.
     */
    @Test
    void theViewCountsEveryRowOfTheWalletLikeTheReadInNetworkOrder() {
        Wallet wallet = wallet();
        Wallet other = wallet();
        record(wallet, RealEntryKind.DEPOSIT, 10 * SOL, "dep:count");
        record(wallet, RealEntryKind.RESERVE, SOL, "order:count");
        fill(wallet, RealEntryKind.SWAP_FILL_IN, -SOL, SyntheticIds.mint(), 10);
        record(other, RealEntryKind.DEPOSIT, SOL, "dep:other");
        java.util.List<com.memetrade.ledger.api.NetworkOrderedEntry> streamed = new java.util.ArrayList<>();
        ledger.streamInNetworkOrder(
                wallet.tenantId(),
                java.util.List.of(wallet.botWalletId()),
                java.util.EnumSet.allOf(RealEntryKind.class),
                streamed::add);

        long counted = ledger.view(wallet.tenantId(), wallet.botWalletId(), java.util.Optional.empty())
                .walletRowCount();

        assertThat(counted).isEqualTo(3L).isEqualTo(streamed.size());
        assertThat(ledger.view(other.tenantId(), wallet.botWalletId(), java.util.Optional.empty())
                        .walletRowCount())
                .isZero();
    }

    @Test
    void anotherTenantSeesNoPositionNorFlow() {
        Wallet mine = wallet();
        Wallet other = wallet();
        record(mine, RealEntryKind.DEPOSIT, SOL, "dep:b");
        fill(mine, RealEntryKind.SWAP_FILL_IN, -SOL, SyntheticIds.mint(), 10);

        assertThat(ledger.positions(other.tenantId(), mine.botWalletId())).isEmpty();
        assertThat(ledger.flowsSince(other.tenantId(), mine.botWalletId(), Instant.EPOCH))
                .isEqualTo(new RealFlows(0, 0));
    }

    @Test
    void theFlowsCountOnlyWhatCameAfterTheInstantAndTheWithdrawalCarriesItsFee() {
        Wallet wallet = wallet();
        record(wallet, RealEntryKind.DEPOSIT, 2 * SOL, "dep:before");
        Instant photo = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        record(wallet, RealEntryKind.DEPOSIT, 3 * SOL, "dep:after");
        record(wallet, RealEntryKind.WITHDRAWAL, -SOL, "wd:1");
        record(wallet, RealEntryKind.NETWORK_FEE, -5_000, "wd:1");
        record(wallet, RealEntryKind.NETWORK_FEE, -7_000, "order:x");

        assertThat(ledger.flowsSince(wallet.tenantId(), wallet.botWalletId(), photo))
                .isEqualTo(new RealFlows(3 * SOL, SOL + 5_000));
    }

    @Test
    void theReservedLamportsAreSplitBetweenOrdersAndWithdrawals() {
        Wallet wallet = wallet();
        record(wallet, RealEntryKind.DEPOSIT, 10 * SOL, "dep:c");
        record(wallet, RealEntryKind.RESERVE, SOL, "order:1");
        record(wallet, RealEntryKind.RESERVE, 2 * SOL, "order:2");
        record(wallet, RealEntryKind.RESERVE_RELEASE, 2 * SOL, "order:2");
        record(wallet, RealEntryKind.RESERVE, 3 * SOL, "wd:9");

        RealBalance balance = ledger.balance(wallet.tenantId(), wallet.botWalletId());

        assertThat(balance.orderReservedLamports()).isEqualTo(SOL);
        assertThat(balance.withdrawalReservedLamports()).isEqualTo(3 * SOL);
        assertThat(balance.reservedLamports()).isEqualTo(4 * SOL);
        assertThat(balance.freeLamports()).isEqualTo(6 * SOL);
    }

    /**
     * Review R-01 of the U3, written before the code: balance, positions and flows in ONE statement (one snapshot of
     * PostgreSQL), so a fill or a deposit committed while the barrier reads can never be in one part and missing from
     * another. The result equals the three separate reads.
     */
    @Test
    void theViewReadsBalancePositionsAndFlowsInOneStatement() {
        Wallet wallet = wallet();
        String mint = SyntheticIds.mint();
        record(wallet, RealEntryKind.DEPOSIT, 10 * SOL, "dep:v1");
        Instant photo = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        record(wallet, RealEntryKind.DEPOSIT, 2 * SOL, "dep:v2");
        record(wallet, RealEntryKind.WITHDRAWAL, -SOL, "wd:v");
        record(wallet, RealEntryKind.NETWORK_FEE, -5_000, "wd:v");
        record(wallet, RealEntryKind.RESERVE, SOL, "order:v");
        fill(wallet, RealEntryKind.SWAP_FILL_IN, -3 * SOL, mint, 300);
        clock.advance(Duration.ofSeconds(1));
        fill(wallet, RealEntryKind.SWAP_FILL_OUT, SOL, mint, 100);

        com.memetrade.ledger.api.RealLedgerView view =
                ledger.view(wallet.tenantId(), wallet.botWalletId(), java.util.Optional.of(photo));

        assertThat(view.balance()).isEqualTo(ledger.balance(wallet.tenantId(), wallet.botWalletId()));
        assertThat(view.positions()).isEqualTo(ledger.positions(wallet.tenantId(), wallet.botWalletId()));
        assertThat(view.flows()).isEqualTo(ledger.flowsSince(wallet.tenantId(), wallet.botWalletId(), photo));
        assertThat(view.flows()).isEqualTo(new RealFlows(2 * SOL, SOL + 5_000));
        assertThat(view.positions())
                .containsExactly(new RealPosition(mint, 200, new RealCost.Unknown(RealCostUnknownReason.NOT_READ)));
        assertThat(ledger.view(wallet.tenantId(), wallet.botWalletId(), java.util.Optional.empty())
                        .flows())
                .as("without a photo there are no flows to read")
                .isEqualTo(RealFlows.NONE);
        assertThat(ledger.view(UUID.randomUUID(), wallet.botWalletId(), java.util.Optional.of(photo)))
                .as("another tenant reads nothing")
                .satisfies(other -> {
                    assertThat(other.balance().totalLamports()).isZero();
                    assertThat(other.positions()).isEmpty();
                    assertThat(other.flows()).isEqualTo(RealFlows.NONE);
                });
    }

    @Test
    void aSwapFillWithoutMintOrQuantityOrWithTheWrongSignIsRefused() {
        Wallet wallet = wallet();

        assertThatThrownBy(() -> fill(wallet, RealEntryKind.SWAP_FILL_IN, SOL, SyntheticIds.mint(), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fill(wallet, RealEntryKind.SWAP_FILL_OUT, -SOL, SyntheticIds.mint(), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fill(wallet, RealEntryKind.SWAP_FILL_IN, -SOL, SyntheticIds.mint(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(wallet, RealEntryKind.SWAP_FILL_IN, -SOL, "order:no-mint"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
