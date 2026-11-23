package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.DepositStore;
import com.memetrade.botwallet.domain.Withdrawal;
import com.memetrade.botwallet.domain.WithdrawalAmount;
import com.memetrade.botwallet.domain.WithdrawalKind;
import com.memetrade.botwallet.domain.WithdrawalState;
import com.memetrade.botwallet.domain.WithdrawalTransition;
import com.memetrade.botwallet.infra.JdbcBotWalletRepository;
import com.memetrade.botwallet.infra.JdbcWorkerStores;
import com.memetrade.ledger.api.RecordSlotResult;
import com.memetrade.ledger.api.SlotWriter;
import com.memetrade.realexits.AbstractRealExitsIT;
import com.memetrade.realexits.application.FillPosting;
import com.memetrade.realexits.domain.ExitSide;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 4 (written before the code; BR1.4, C13, C14, NFR1.21, reliability-design.md
 * "Conflito de slot"): the three writers record the slot of the signature in {@code real_entry_slot} in the same
 * transaction as their state change and their entries:
 *
 * <ul>
 *   <li>the confirmation of a REAL order, CONFIRMED and FAILED with a transaction ({@code ORDER_CONFIRMATION});
 *   <li>the fill of a conditional ({@code CONDITIONAL_FILL});
 *   <li>the confirmation of a deposit and of a withdrawal with its fee ({@code WALLET_CONFIRMATION}).
 * </ul>
 *
 * RECORDED, ALREADY_RECORDED and CONFLICT_REFUSED all let the order or the movement reach its final state with its
 * entries; a conflict keeps the first slot and never throws; a slot unknown at the confirmation records nothing. The slot
 * comes only from the answers the network already gave (the test doubles), never from a new call.
 */
@Tag("u2-real-bot-pnl")
class RecordSlotIT extends AbstractRealExitsIT {

    @Autowired
    private FillPosting fills;

    @Autowired
    private JdbcConditionalStore conditionals;

    @Autowired
    private JdbcWorkerStores stores;

    @Autowired
    private JdbcBotWalletRepository repository;

    // ----- the port itself -------------------------------------------------------------------------------------------

    @Test
    void thePortAnswersRecordedThenAlreadyRecordedThenConflictRefusedAndKeepsTheFirstSlot() {
        Setup setup = realBot("AUTO_FULL");
        String signature = SyntheticIds.signature();
        long before = conflictEvents();

        RecordSlotResult first = record(setup, signature, 500L);
        RecordSlotResult same = record(setup, signature, 500L);
        RecordSlotResult other = record(setup, signature, 501L);

        assertThat(first).isEqualTo(RecordSlotResult.RECORDED);
        assertThat(same).isEqualTo(RecordSlotResult.ALREADY_RECORDED);
        assertThat(other).isEqualTo(RecordSlotResult.CONFLICT_REFUSED);
        assertThat(slotOf(signature)).contains(500L);
        assertThat(conflictEvents()).isEqualTo(before + 1);
    }

    // ----- ORDER_CONFIRMATION ----------------------------------------------------------------------------------------

    @Test
    void aConfirmedOrderRecordsTheSlotReadWithItsEffect() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        String signature = txSignature(order);
        chain.effect(signature, effect(OptionalLong.of(9_001L)));
        chain.finalize(signature);

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(entries(order)).containsKeys("SWAP_FILL_IN", "NETWORK_FEE", "RESERVE_RELEASE");
        assertThat(slotOf(signature)).contains(9_001L);
    }

    @Test
    void aConfirmedOrderWhoseSlotIsAlreadyRecordedKeepsItAndConfirms() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        String signature = txSignature(order);
        insertSlot(signature, 9_002L);
        chain.effect(signature, effect(OptionalLong.of(9_002L)));
        chain.finalize(signature);
        long before = conflictEvents();

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(entries(order)).containsKey("SWAP_FILL_IN");
        assertThat(slotOf(signature)).contains(9_002L);
        assertThat(conflictEvents()).isEqualTo(before);
    }

    @Test
    void aConfirmedOrderWithAnotherSlotKeepsTheFirstConfirmsAndIsAudited() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        String signature = txSignature(order);
        insertSlot(signature, 9_003L);
        chain.effect(signature, effect(OptionalLong.of(9_999L)));
        chain.finalize(signature);
        long before = conflictEvents();

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(entries(order)).containsKeys("SWAP_FILL_IN", "NETWORK_FEE", "RESERVE_RELEASE");
        assertThat(slotOf(signature)).contains(9_003L);
        assertThat(conflictEvents()).isEqualTo(before + 1);
    }

    @Test
    void anOrderFailedOnChainRecordsTheSlotOfItsTransaction() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        String signature = txSignature(order);
        chain.effect(signature, new RealChain.SwapEffect(-5_000L, 0L, 5_000L, true, OptionalLong.of(9_004L)));
        chain.fail(signature, true);

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(entries(order)).containsEntry("NETWORK_FEE", -5_000L);
        assertThat(slotOf(signature)).contains(9_004L);
    }

    @Test
    void aSlotUnknownAtTheConfirmationRecordsNothingAndTheOrderConfirms() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        String signature = txSignature(order);
        chain.effect(signature, effect(OptionalLong.empty()));
        chain.finalize(signature);

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(entries(order)).containsKey("SWAP_FILL_IN");
        assertThat(slotOf(signature)).isEmpty();
    }

    // ----- CONDITIONAL_FILL ------------------------------------------------------------------------------------------

    @Test
    void theFillOfAConditionalRecordsItsSlot() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        String signature = SyntheticIds.signature();

        boolean posted = fills.post(
                conditionals.find(conditional).orElseThrow(),
                ExitSide.STOP,
                signature,
                new RealChain.SwapEffect(1_195_000L, 0L, 5_000L, true, OptionalLong.of(9_005L)));

        assertThat(posted).isTrue();
        assertThat(conditionalState(conditional)).isEqualTo("FILLED");
        assertThat(conditionalEntries(conditional)).containsKeys("SWAP_FILL_OUT", "NETWORK_FEE");
        assertThat(slotOf(signature)).contains(9_005L);
    }

    @Test
    void theFillOfAConditionalWithAnotherSlotKeepsTheFirstAndIsStillFilled() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        String signature = SyntheticIds.signature();
        insertSlot(signature, 9_006L);
        long before = conflictEvents();

        boolean posted = fills.post(
                conditionals.find(conditional).orElseThrow(),
                ExitSide.TAKE_PROFIT,
                signature,
                new RealChain.SwapEffect(1_200_000L, 0L, 5_000L, false, OptionalLong.of(9_007L)));

        assertThat(posted).isTrue();
        assertThat(conditionalState(conditional)).isEqualTo("FILLED");
        assertThat(conditionalEntries(conditional)).containsKey("SWAP_FILL_OUT");
        assertThat(slotOf(signature)).contains(9_006L);
        assertThat(conflictEvents()).isEqualTo(before + 1);
    }

    // ----- WALLET_CONFIRMATION ---------------------------------------------------------------------------------------

    @Test
    void aDepositRecordsTheSlotOfItsTransaction() {
        Setup setup = realBot("AUTO_FULL");
        String signature = TestWallet.generate().sign(UUID.randomUUID().toString());

        boolean recorded = stores.record(
                toScan(setup),
                new DepositStore.RecordedDeposit(
                        new ChainPort.IncomingTransfer(
                                signature,
                                setup.main().address(),
                                1_000_000L,
                                Instant.parse("2026-11-20T10:00:00Z"),
                                OptionalLong.of(9_008L)),
                        true,
                        false));

        assertThat(recorded).isTrue();
        assertThat(jdbc.sql("select count(*) from real_ledger_entry where reference = :ref")
                        .param("ref", "dep:" + signature)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(slotOf(signature)).contains(9_008L);
    }

    @Test
    void aDepositWithAnotherSlotIsStillRecordedAndKeepsTheFirstSlot() {
        Setup setup = realBot("AUTO_FULL");
        String signature = TestWallet.generate().sign(UUID.randomUUID().toString());
        insertSlot(signature, 9_009L);
        long before = conflictEvents();

        boolean recorded = stores.record(
                toScan(setup),
                new DepositStore.RecordedDeposit(
                        new ChainPort.IncomingTransfer(
                                signature,
                                setup.main().address(),
                                2_000_000L,
                                Instant.parse("2026-11-20T10:00:00Z"),
                                OptionalLong.of(9_010L)),
                        true,
                        false));

        assertThat(recorded).isTrue();
        assertThat(slotOf(signature)).contains(9_009L);
        assertThat(conflictEvents()).isEqualTo(before + 1);
    }

    @Test
    void aConfirmedWithdrawalRecordsTheSlotWithItsEntriesAndItsFee() {
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
        String signature = TestWallet.generate().sign(UUID.randomUUID().toString());
        Withdrawal sent = requested
                .withdrawal()
                .signed("c2lnbmVk", "aa".repeat(32), "hash", 10L, signature, clock.instant())
                .sent(clock.instant());
        stores.save(WithdrawalTransition.unchanged(sent));

        stores.save(sent.confirmed(signature, 4_800L, OptionalLong.of(9_011L), clock.instant()));

        assertThat(repository
                        .withdrawal(setup.tenantId(), setup.botWalletId(), sent.withdrawalId())
                        .orElseThrow()
                        .state())
                .isEqualTo(WithdrawalState.CONFIRMED);
        assertThat(jdbc.sql("select count(*) from real_ledger_entry where reference = :ref"
                                + " and entry_kind in ('WITHDRAWAL', 'NETWORK_FEE')")
                        .param("ref", sent.reference())
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        assertThat(slotOf(signature)).contains(9_011L);
    }

    // ----- helpers ---------------------------------------------------------------------------------------------------

    private UUID sent(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        return order;
    }

    private static RealChain.SwapEffect effect(OptionalLong slot) {
        return new RealChain.SwapEffect(-52_047_737L, 1_000_000L, 8_457L, true, slot);
    }

    private RecordSlotResult record(Setup setup, String signature, long slot) {
        return transactions.execute(
                status -> ledger.recordSlot(setup.tenantId(), signature, slot, SlotWriter.ORDER_CONFIRMATION));
    }

    private DepositStore.WalletToScan toScan(Setup setup) {
        return new DepositStore.WalletToScan(
                setup.tenantId(),
                setup.botWalletId(),
                setup.bot().address(),
                setup.main().address(),
                null);
    }

    private void insertSlot(String signature, long slot) {
        jdbc.sql("insert into real_entry_slot (tx_signature, slot, recorded_at) values (:s, :slot, :at)")
                .param("s", signature)
                .param("slot", slot)
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    private Optional<Long> slotOf(String signature) {
        return jdbc.sql("select slot from real_entry_slot where tx_signature = :s")
                .param("s", signature)
                .query(Long.class)
                .optional();
    }

    private long conflictEvents() {
        return jdbc.sql("select count(*) from audit_outbox where payload->>'action' = 'REAL_SLOT_CONFLICT'")
                .query(Long.class)
                .single();
    }
}
