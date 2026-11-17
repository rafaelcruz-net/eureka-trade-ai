package com.memetrade.realexits.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.realexits.AbstractRealExitsIT;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ExitSide;
import com.memetrade.realexits.domain.TriggerPort.ProviderStatus;
import com.memetrade.realexits.infra.ConditionalReadJob;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 7 of the U7, written BEFORE the code (BR5, S13 to S15, S20, S27, U7-NFR3.1, U7-NFR4.2, U7-NFR4.4, U7-NFR4.6):
 * the read job asks the provider for the conditionals OPEN and CANCELLING; a fill counts only after the network
 * confirms its signature (finalized), with the quantity, the SOL and the fee read from the network, never from the
 * provider. The fill enters the REAL ledger through M2 with the kinds that exist ({@code SWAP_FILL_OUT}, and
 * {@code NETWORK_FEE} only when the bot wallet paid), reference {@code conditional:<id>} and keys
 * {@code cond:<signature>:fill} and {@code :fee}, in the same transaction as FILLED. The answers of the network are
 * synthetic (the fake M3 of the U4); no test calls the Jupiter, the devnet nor the mainnet.
 */
@Tag("u7-exits-and-fiscal")
class FillPostingIT extends AbstractRealExitsIT {

    private static final long RECEIVED = 1_200_000L;
    private static final long FEE = 5_000L;

    @Autowired
    private ConditionalReadJob reads;

    @Autowired
    private FillPosting fills;

    @Autowired
    private JdbcConditionalStore store;

    /** The provider says the conditional filled and the network has the transaction finalized. */
    private String filledOnTheNetwork(UUID conditional, ExitSide side, boolean feePaidByBot) {
        String signature = SyntheticIds.signature();
        trigger.statuses.put(conditional, new ProviderStatus.Filled(side, signature));
        chain.finalize(signature);
        long botDelta = feePaidByBot ? RECEIVED - FEE : RECEIVED;
        chain.effect(signature, new RealChain.SwapEffect(botDelta, 0L, FEE, feePaidByBot));
        return signature;
    }

    private long position(Setup setup) {
        return ledger.positions(setup.tenantId(), setup.botWalletId()).stream()
                .filter(open -> open.mint().equals(setup.mint()))
                .mapToLong(RealPosition::tokenBaseUnits)
                .sum();
    }

    private Map<String, String> keysOf(UUID conditional) {
        Map<String, String> keys = new java.util.TreeMap<>();
        jdbc.sql("select entry_kind, idempotency_key from real_ledger_entry where reference = :ref")
                .param("ref", "conditional:" + conditional)
                .query((rs, row) -> keys.put(rs.getString("entry_kind"), rs.getString("idempotency_key")))
                .list();
        return keys;
    }

    /** S13: the stop filled; the fill and the fee paid by the bot wallet enter the ledger; the position is zero. */
    @Test
    void s13TheFillEntersTheLedgerWithItsFeeAndClosesThePosition() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        String signature = filledOnTheNetwork(conditional, ExitSide.STOP, true);

        reads.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("FILLED");
        assertThat(conditionalEntries(conditional))
                .containsExactlyInAnyOrderEntriesOf(Map.of("SWAP_FILL_OUT", RECEIVED, "NETWORK_FEE", -FEE));
        assertThat(keysOf(conditional))
                .containsEntry("SWAP_FILL_OUT", "cond:" + signature + ":fill")
                .containsEntry("NETWORK_FEE", "cond:" + signature + ":fee");
        assertThat(jdbc.sql("select filled_side || ':' || fill_signature from real_conditional_order where"
                                + " conditional_order_id = :id")
                        .param("id", conditional)
                        .query(String.class)
                        .single())
                .isEqualTo("STOP:" + signature);
        assertThat(jdbc.sql("select token_base_units from real_ledger_entry where reference = :ref and entry_kind ="
                                + " 'SWAP_FILL_OUT'")
                        .param("ref", "conditional:" + conditional)
                        .query(Long.class)
                        .single())
                .isEqualTo(5_000L);
        assertThat(position(setup)).isZero();
    }

    /** BR5.3: when the Jupiter paid the execution there is no fee entry. */
    @Test
    void withTheExecutionPaidByTheJupiterThereIsNoFeeEntry() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        filledOnTheNetwork(conditional, ExitSide.STOP, false);

        reads.runOnce();

        assertThat(conditionalEntries(conditional)).containsExactly(Map.entry("SWAP_FILL_OUT", RECEIVED));
    }

    /** S14, U7-NFR4.2: the same fill posted twice (two workers, a repeated read) gives one set of entries. */
    @Test
    void s14TheSameFillPostedTwiceEntersTheLedgerOnce() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        String signature = filledOnTheNetwork(conditional, ExitSide.STOP, true);
        ConditionalOrder read = store.find(conditional).orElseThrow();
        RealChain.SwapEffect effect = new RealChain.SwapEffect(RECEIVED - FEE, 0L, FEE, true);

        boolean first = fills.post(read, ExitSide.STOP, signature, effect);
        boolean again = fills.post(read, ExitSide.STOP, signature, effect);
        reads.runOnce();

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(jdbc.sql("select count(*) from real_ledger_entry where reference = :ref")
                        .param("ref", "conditional:" + conditional)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    /** S15, BR5.2: the provider says filled but the network has not finalized: nothing changes. */
    @Test
    void s15AFillNotFinalizedOnTheNetworkWaitsAndEntersNothing() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        String signature = SyntheticIds.signature();
        trigger.statuses.put(conditional, new ProviderStatus.Filled(ExitSide.STOP, signature));

        reads.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("OPEN");
        assertThat(conditionalEntries(conditional)).isEmpty();
        chain.finalize(signature);
        chain.effect(signature, new RealChain.SwapEffect(RECEIVED, 0L, FEE, false));
        reads.runOnce();
        assertThat(conditionalState(conditional)).isEqualTo("FILLED");
    }

    /** S20, BR6.6: a conditional being cancelled fills first: the fill counts. */
    @Test
    void s20AConditionalFilledWhileCancellingCountsTheFill() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "CANCELLING", 5_000L);
        filledOnTheNetwork(conditional, ExitSide.TAKE_PROFIT, true);

        reads.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("FILLED");
        assertThat(conditionalEntries(conditional)).containsEntry("SWAP_FILL_OUT", RECEIVED);
    }

    /** BR5.4: in an OCO the leg that filled is recorded; the other ceases to exist. */
    @Test
    void theTakeProfitLegOfAnOcoIsRecordedAsTheFilledSide() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        filledOnTheNetwork(conditional, ExitSide.TAKE_PROFIT, false);

        reads.runOnce();

        assertThat(jdbc.sql("select filled_side from real_conditional_order where conditional_order_id = :id")
                        .param("id", conditional)
                        .query(String.class)
                        .single())
                .isEqualTo("TAKE_PROFIT");
    }

    /** S27, U7-NFR4.6: the provider down changes nothing and the last successful read does not move. */
    @Test
    void s27TheProviderDownChangesNothingAndTheLastReadStays() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        Timestamp before = lastRead(conditional);
        trigger.down.set(true);
        clock.advance(Duration.ofSeconds(30));

        reads.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("OPEN");
        assertThat(lastRead(conditional)).isEqualTo(before);
    }

    /** BR5.6: a successful read moves the last read of an OPEN conditional. */
    @Test
    void aSuccessfulReadMovesTheLastRead() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        clock.advance(Duration.ofSeconds(30));

        reads.runOnce();

        assertThat(lastRead(conditional).toInstant()).isEqualTo(clock.instant());
    }

    /** The provider says the conditional expired: EXPIRED, nothing in the ledger. */
    @Test
    void aConditionalExpiredAtTheProviderIsExpired() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        trigger.statuses.put(conditional, new ProviderStatus.Expired());

        reads.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("EXPIRED");
        assertThat(conditionalEntries(conditional)).isEmpty();
    }

    /** team.md: two people in one run; each fill goes to the ledger of its own wallet. */
    @Test
    void eachFillGoesToTheLedgerOfItsOwnWallet() {
        Setup first = realBot("AUTO_EXITS");
        Setup second = realBot("AUTO_EXITS");
        position(wallet(first), first.mint(), 5_000L, 1_000_000L);
        position(wallet(second), second.mint(), 7_000L, 1_000_000L);
        UUID one = conditional(first, "OPEN", 5_000L);
        UUID two = conditional(second, "OPEN", 7_000L);
        filledOnTheNetwork(one, ExitSide.STOP, true);
        filledOnTheNetwork(two, ExitSide.STOP, true);

        reads.runOnce();

        assertThat(position(first)).isZero();
        assertThat(position(second)).isZero();
        assertThat(jdbc.sql("select count(distinct bot_wallet_id) from real_ledger_entry where reference in (:refs)")
                        .param("refs", java.util.List.of("conditional:" + one, "conditional:" + two))
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    /** U7-NFR4.2: the compare-and-set comes first; a conditional moved meanwhile leaves nothing half written. */
    @Test
    void aFillOfAConditionalMovedMeanwhileEntersNothing() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        ConditionalOrder stale = store.find(conditional).orElseThrow();
        jdbc.sql("update real_conditional_order set state = 'EXPIRED', final_at = now() where"
                        + " conditional_order_id = :id")
                .param("id", conditional)
                .update();

        boolean posted = fills.post(
                stale, ExitSide.STOP, SyntheticIds.signature(), new RealChain.SwapEffect(RECEIVED, 0L, FEE, false));

        assertThat(posted).isFalse();
        assertThat(conditionalEntries(conditional)).isEmpty();
        assertThat(conditionalState(conditional)).isEqualTo("EXPIRED");
    }

    private Timestamp lastRead(UUID conditional) {
        return jdbc.sql("select last_read_at from real_conditional_order where conditional_order_id = :id")
                .param("id", conditional)
                .query(Timestamp.class)
                .single();
    }
}
