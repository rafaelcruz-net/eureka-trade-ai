package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.DepositStore;
import com.memetrade.botwallet.domain.Withdrawal;
import com.memetrade.botwallet.domain.WithdrawalAmount;
import com.memetrade.botwallet.domain.WithdrawalKind;
import com.memetrade.botwallet.domain.WithdrawalTransition;
import com.memetrade.botwallet.infra.JdbcBotWalletRepository;
import com.memetrade.botwallet.infra.JdbcWorkerStores;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.realexits.AbstractRealExitsIT;
import com.memetrade.realexits.application.FillPosting;
import com.memetrade.realexits.domain.ExitSide;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2a, Etapa 14b (decision of the founder of 2026-11-22): the three writers of the REAL ledger
 * keep the marks of zero in their own transaction, after their entries and their slot. A zero of the quantity of the
 * wallet in a mint (BR2.5) is marked with the network order key of the row that zeroed it, by the confirmation of an
 * order, the fill of a conditional and the confirmation of a deposit or of a withdrawal.
 */
@Tag("u2-real-bot-pnl")
class EpochMarkWritersIT extends AbstractRealExitsIT {

    @Autowired
    private FillPosting fills;

    @Autowired
    private JdbcConditionalStore conditionals;

    @Autowired
    private JdbcWorkerStores stores;

    @Autowired
    private JdbcBotWalletRepository repository;

    @Test
    void theConfirmationOfAnOrderMarksTheZeroOfItsMint() {
        Setup setup = realBot("AUTO_FULL");
        String overSale = overSale(setup, setup.mint());
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        String signature = jdbc.sql("select tx_signature from real_order where real_order_id = :id")
                .param("id", order)
                .query(String.class)
                .single();
        chain.effect(
                signature, new RealChain.SwapEffect(-52_047_737L, 1_000_000L, 8_457L, true, OptionalLong.of(9_101L)));
        chain.finalize(signature);

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(marks(setup, setup.mint())).containsExactly("ZERO|" + overSale);
    }

    @Test
    void theFillOfAConditionalThatSellsThePositionMarksItsZero() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        String signature = SyntheticIds.signature();

        boolean posted = fills.post(
                conditionals.find(conditional).orElseThrow(),
                ExitSide.STOP,
                signature,
                new RealChain.SwapEffect(1_195_000L, 0L, 5_000L, true, OptionalLong.of(9_102L)));

        assertThat(posted).isTrue();
        assertThat(marks(setup, setup.mint())).containsExactly("ZERO|cond:" + signature + ":fill");
    }

    @Test
    void theConfirmationOfADepositKeepsTheMarksOfTheWallet() {
        Setup setup = realBot("AUTO_FULL");
        String mint = SyntheticIds.mint();
        String overSale = overSale(setup, mint);
        String signature = TestWallet.generate().sign(UUID.randomUUID().toString());

        boolean recorded = stores.record(
                new DepositStore.WalletToScan(
                        setup.tenantId(),
                        setup.botWalletId(),
                        setup.bot().address(),
                        setup.main().address(),
                        null),
                new DepositStore.RecordedDeposit(
                        new ChainPort.IncomingTransfer(
                                signature,
                                setup.main().address(),
                                1_000_000L,
                                Instant.parse("2026-11-20T10:00:00Z"),
                                OptionalLong.of(9_103L)),
                        true,
                        false));

        assertThat(recorded).isTrue();
        assertThat(marks(setup, mint)).containsExactly("ZERO|" + overSale);
    }

    @Test
    void theConfirmationOfAWithdrawalKeepsTheMarksOfTheWallet() {
        Setup setup = realBot("AUTO_FULL");
        WithdrawalTransition requested = Withdrawal.request(
                setup.tenantId(),
                setup.botWalletId(),
                new WithdrawalAmount(WithdrawalKind.PARTIAL, 1_000L, 5_000L),
                UUID.randomUUID(),
                clock.instant());
        transactions.executeWithoutResult(status -> {
            repository.insertWithdrawal(requested.withdrawal());
            requested.entries().forEach(ledger::record);
        });
        String mint = SyntheticIds.mint();
        String overSale = overSale(setup, mint);
        String signature = TestWallet.generate().sign(UUID.randomUUID().toString());
        Withdrawal sent = requested
                .withdrawal()
                .signed("c2lnbmVk", "aa".repeat(32), "hash", 10L, signature, clock.instant())
                .sent(clock.instant());
        stores.save(WithdrawalTransition.unchanged(sent));

        stores.save(sent.confirmed(signature, 4_800L, OptionalLong.of(9_104L), clock.instant()));

        assertThat(marks(setup, mint)).containsExactly("ZERO|" + overSale);
    }

    /** A sale of a mint the wallet does not hold, written without the marks: a zero no mark records yet. */
    /**
     * The sale has its own recorded slot, below the slots of the writers above (9 101 to 9 104), so it comes first in
     * network order whatever the clock of the test: with the clock fixed, a sale without a slot ties the buy on
     * createdAt, the random reference breaks the tie, and half the time the sale inherits the slot of the buy and comes
     * after it (BR1.1, BR1.3), which leaves 999 999 units and no zero.
     */
    private String overSale(Setup setup, String mint) {
        String reference = "order:" + UUID.randomUUID();
        String key = reference + ":fill";
        String signature = SyntheticIds.signature();
        transactions.executeWithoutResult(status -> {
            ledger.record(new RealEntry(
                    setup.tenantId(),
                    setup.botWalletId(),
                    RealEntryKind.SWAP_FILL_OUT,
                    1_000L,
                    signature,
                    reference,
                    key,
                    mint,
                    1L));
            ledger.recordSlot(
                    setup.tenantId(), signature, 9_000L, com.memetrade.ledger.api.SlotWriter.ORDER_CONFIRMATION);
        });
        return key;
    }

    private List<String> marks(Setup setup, String mint) {
        return jdbc.sql("select reason || '|' || zero_idempotency_key from real_position_epoch"
                        + " where bot_wallet_id = :w and mint = :m order by epoch_seq")
                .param("w", setup.botWalletId())
                .param("m", mint)
                .query(String.class)
                .list();
    }
}
