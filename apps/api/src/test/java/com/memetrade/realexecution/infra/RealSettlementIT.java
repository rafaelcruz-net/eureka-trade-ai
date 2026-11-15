package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 11 of the U4, written BEFORE the code (BR6.1, S5, S15, S28): the confirmation records the values read from the
 * network in the REAL ledger, each entry with the signature and the key {@code <realOrderId>:<kind>}; the fee of the
 * SIGNED mode stays on the order (paid by the main wallet) and out of the ledger of the bot wallet.
 */
@Tag("u4-real-execution")
class RealSettlementIT extends AbstractRealExecutionIT {

    @org.springframework.beans.factory.annotation.Autowired
    private com.memetrade.pnl.application.RealPnlService pnl;

    private UUID sent(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        return order;
    }

    @Test
    void s15AConfirmedBuyRecordsTheFillTheFeeTheRentAndTheRelease() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        chain.effect(txSignature(order), new RealChain.SwapEffect(-52_047_737L, 1_000_000L, 8_457L, true));
        chain.finalize(txSignature(order));

        confirmation.runOnce();

        assertThat(state(order)).isEqualTo("CONFIRMED");
        assertThat(entries(order))
                .containsEntry("SWAP_FILL_IN", -50_000_000L)
                .containsEntry("NETWORK_FEE", -8_457L)
                .containsEntry("RENT", -2_039_280L)
                .containsEntry("RESERVE", 50_000_000L)
                .containsEntry("RESERVE_RELEASE", 50_000_000L);
        assertThat(jdbc.sql("select count(*) from real_ledger_entry where reference = :ref and tx_signature = :sig"
                                + " and entry_kind <> 'RESERVE'")
                        .param("ref", "order:" + order)
                        .param("sig", txSignature(order))
                        .query(Long.class)
                        .single())
                .isEqualTo(4);
        assertThat(jdbc.sql("select out_base_units from real_order where real_order_id = :id")
                        .param("id", order)
                        .query(Long.class)
                        .single())
                .isEqualTo(1_000_000L);
    }

    /** Review (Minor 6), before the code: the rent read from the network stays on the order and enters the P&L cost. */
    @Test
    void theRentOfAConfirmedBuyIsOnTheOrderAndInTheCostOfThePosition() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        chain.effect(txSignature(order), new RealChain.SwapEffect(-52_047_737L, 1_000_000L, 8_457L, true));
        chain.finalize(txSignature(order));

        confirmation.runOnce();

        assertThat(jdbc.sql("select rent_lamports from real_order where real_order_id = :id")
                        .param("id", order)
                        .query(Long.class)
                        .single())
                .isEqualTo(2_039_280L);
        assertThat(pnl.pnl(setup.tenantId(), setup.userId()).positions())
                .singleElement()
                .satisfies(position -> assertThat(position.costLamports()).isEqualTo(52_047_737L));
    }

    /**
     * Review (Minor 5), before the code: the bot wallet of a buy is no longer ACTIVE when the order reaches a final
     * state; the RESERVE_RELEASE is still recorded, for the wallet of the order (never a reserve left held).
     */
    @Test
    void aFinalOrderReleasesItsReserveEvenWhenTheWalletIsNoLongerActive() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        jdbc.sql("update bot_wallet_account set status = 'CLOSED', closed_at = now() where bot_wallet_id = :w")
                .param("w", setup.botWalletId())
                .update();

        driver.runOnce();

        assertThat(state(order)).isIn("FAILED", "CANCELLED");
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
        assertThat(jdbc.sql("select bot_wallet_id from real_ledger_entry where idempotency_key = :key")
                        .param("key", order + ":RESERVE_RELEASE")
                        .query(UUID.class)
                        .single())
                .isEqualTo(setup.botWalletId());
    }

    @Test
    void s5AFailureForSlippageRecordsOnlyTheFeeAndTheRelease() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        chain.effect(txSignature(order), new RealChain.SwapEffect(-5_000L, 0L, 5_000L, true));
        chain.fail(txSignature(order), true);

        confirmation.runOnce();

        assertThat(entries(order))
                .containsOnlyKeys("RESERVE", "RESERVE_RELEASE", "NETWORK_FEE")
                .containsEntry("NETWORK_FEE", -5_000L);
    }

    @Test
    void s28ASignedBuyKeepsTheFeeOnTheOrderAndOutOfTheLedgerOfTheBotWallet() {
        Setup setup = realBot("SIGNED");
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        jdbc.sql("update real_order set state = 'SIGNED', tx_signature = :sig, signed_transaction = unsigned_message"
                        + " where real_order_id = :id")
                .param("sig", com.memetrade.testsupport.SyntheticIds.signature())
                .param("id", order)
                .update();
        driver.runOnce();
        chain.effect(txSignature(order), new RealChain.SwapEffect(-50_000_000L, 1_000_000L, 13_457L, false));
        chain.finalize(txSignature(order));

        confirmation.runOnce();

        assertThat(entries(order)).doesNotContainKey("NETWORK_FEE").containsEntry("SWAP_FILL_IN", -50_000_000L);
        assertThat(jdbc.sql("select fee_payer || ':' || fee_lamports from real_order where real_order_id = :id")
                        .param("id", order)
                        .query(String.class)
                        .single())
                .isEqualTo("MAIN_WALLET:13457");
    }

    @Test
    void theEntriesOfAnOrderAreNeverRecordedTwice() {
        Setup setup = realBot("AUTO_FULL");
        UUID order = sent(setup);
        chain.finalize(txSignature(order));
        confirmation.runOnce();
        long before = jdbc.sql("select count(*) from real_ledger_entry where reference = :ref")
                .param("ref", "order:" + order)
                .query(Long.class)
                .single();

        confirmation.runOnce();
        ambiguous.runOnce();

        assertThat(jdbc.sql("select count(*) from real_ledger_entry where reference = :ref")
                        .param("ref", "order:" + order)
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
    }
}
