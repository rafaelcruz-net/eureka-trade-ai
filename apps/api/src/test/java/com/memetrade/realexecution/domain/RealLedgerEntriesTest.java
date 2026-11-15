package com.memetrade.realexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.realexecution.domain.RealLedgerEntries.Entry;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 11 of the U4, written BEFORE the code (BR6.1, adendo da U3 "convenção dos preenchimentos"): the REAL ledger gets
 * the values of the network. A confirmed buy: SWAP_FILL_IN (lamports out, token in), the fee when the bot wallet paid it
 * and the rent of the created account; a confirmed sell: SWAP_FILL_OUT (token out, lamports in); a failure on chain:
 * only the fee; a cancellation: nothing (the RESERVE_RELEASE is the transition's).
 */
@Tag("u4-real-execution")
class RealLedgerEntriesTest {

    @Test
    void s15AConfirmedAutoFullBuyRecordsTheFillTheFeeAndTheRent() {
        // 0.05 SOL in, fee 8457 paid by the bot, rent 2039280 of the new token account.
        List<Entry> entries = RealLedgerEntries.of(
                RealSide.BUY, RealOrderState.CONFIRMED, 50_000_000L, -52_047_737L, 1_000_000L, 8_457L, true);

        assertThat(entries)
                .containsExactly(
                        new Entry(RealEntryKind.SWAP_FILL_IN, -50_000_000L, 1_000_000L),
                        new Entry(RealEntryKind.NETWORK_FEE, -8_457L, null),
                        new Entry(RealEntryKind.RENT, -2_039_280L, null));
    }

    @Test
    void s28AConfirmedSignedBuyHasNoNetworkFeeInTheLedgerOfTheBotWallet() {
        List<Entry> entries = RealLedgerEntries.of(
                RealSide.BUY, RealOrderState.CONFIRMED, 50_000_000L, -50_000_000L, 1_000_000L, 13_457L, false);

        assertThat(entries).containsExactly(new Entry(RealEntryKind.SWAP_FILL_IN, -50_000_000L, 1_000_000L));
    }

    @Test
    void aConfirmedSellRecordsTheLamportsReceivedAndTheTokensSold() {
        // 47 900 000 lamports received; the bot paid the fee of 5 000 out of them.
        List<Entry> entries = RealLedgerEntries.of(
                RealSide.SELL, RealOrderState.CONFIRMED, 1_000_000L, 47_895_000L, -1_000_000L, 5_000L, true);

        assertThat(entries)
                .containsExactly(
                        new Entry(RealEntryKind.SWAP_FILL_OUT, 47_900_000L, 1_000_000L),
                        new Entry(RealEntryKind.NETWORK_FEE, -5_000L, null));
    }

    @Test
    void s5AFailureOnChainRecordsOnlyTheFeeOfTheBotWallet() {
        assertThat(RealLedgerEntries.of(RealSide.BUY, RealOrderState.FAILED, 50_000_000L, -5_000L, 0L, 5_000L, true))
                .containsExactly(new Entry(RealEntryKind.NETWORK_FEE, -5_000L, null));
        assertThat(RealLedgerEntries.of(RealSide.BUY, RealOrderState.FAILED, 50_000_000L, 0L, 0L, 10_000L, false))
                .isEmpty();
    }

    @Test
    void aCancelledOrderRecordsNothingHere() {
        assertThat(RealLedgerEntries.of(RealSide.BUY, RealOrderState.CANCELLED, 50_000_000L, 0L, 0L, 0L, true))
                .isEmpty();
    }

    @Test
    void aBuyThatReceivedNoTokenRecordsNoFill() {
        assertThat(RealLedgerEntries.of(RealSide.BUY, RealOrderState.CONFIRMED, 50_000_000L, -5_000L, 0L, 5_000L, true))
                .containsExactly(new Entry(RealEntryKind.NETWORK_FEE, -5_000L, null));
    }
}
