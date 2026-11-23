package com.memetrade.riskcontrol.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.riskcontrol.real.domain.RealRiskPolicy;
import com.memetrade.riskcontrol.real.infra.RealRiskJobs;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 12 (written before the code; decision F-4 of the founder, reliability-design.md
 * "Fotografia do dia adiada", review 02 R-11): a position without price and an unknown cost at the round of the day
 * (here an order of the wallet names a bot that is not a REAL bot of the person: UNKNOWN_BOT): the round writes the mark
 * of the day and no photo. The price comes back lower: the first buy of the day is refused (the price of a position,
 * the photo pending) and takes no FIRST_EVALUATION photo; a sale and a liquidation go on. The cost becomes known: the
 * round takes the DAY_JOB photo with each position at the larger of its quote and its cost; the loss since the start of
 * the day shows in the judge and pauses the wallet with DAILY_LOSS. Without the mark the first buy still takes the
 * FIRST_EVALUATION photo.
 */
@Tag("u2-real-bot-pnl")
class DaySnapshotDeferredIT extends AbstractRealExecutionIT {

    private static final long POSITION_COST = 2 * SOL;

    @Autowired
    private RealRiskJobs jobs;

    @Autowired
    private RealRiskGate gate;

    @Test
    void aDeferredPhotoRefusesTheFirstBuyUntilTheRoundTakesItWithTheCostKnown() {
        Setup setup = realBot("AUTO_FULL");
        String unpriced = SyntheticIds.mint();
        UUID unknownBot = UUID.randomUUID();
        orderOfABotOutsideTheDirectory(setup, unknownBot, unpriced);
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        jobs.runDay();

        assertThat(deferred(setup, today)).isTrue();
        assertThat(photos(setup, today)).isZero();

        // The price comes back, much lower than the cost.
        observe(unpriced, "0.0000001", 6, 5);
        GateDecision buy = transactions.execute(status -> gate.evaluate(OrderIntent.botBuy(
                setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), setup.mint(), 50_000_000L)));
        GateDecision sale = transactions.execute(status -> gate.evaluate(OrderIntent.botSell(
                setup.tenantId(), setup.userId(), setup.botId(), UUID.randomUUID(), unpriced, 500)));
        GateDecision liquidation = transactions.execute(status ->
                gate.evaluate(OrderIntent.userLiquidation(setup.tenantId(), setup.userId(), unpriced, 1_000)));

        assertThat(buy).isInstanceOfSatisfying(GateDecision.Rejected.class, rejected -> {
            assertThat(rejected.detail()).isEqualTo(RealRejectionDetail.POSITION_PRICE_UNKNOWN);
            assertThat(rejected.figures()).containsEntry(RealRiskPolicy.DAY_SNAPSHOT_PENDING, 1L);
        });
        assertThat(photos(setup, today))
                .as("no FIRST_EVALUATION photo after the fall")
                .isZero();
        assertThat(sale.approved()).isTrue();
        assertThat(liquidation.approved()).isTrue();

        // The cost becomes known: the bot of the order is now a REAL bot of the person.
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, 'robo antigo', 'MOMENTUM_VOLUME', '1', 'ACTIVE', now(), now(), 'REAL')""")
                .param("b", unknownBot)
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .update();
        long balance = ledger.balance(setup.tenantId(), setup.botWalletId()).totalLamports();

        jobs.runDay();

        assertThat(jdbc.sql("select start_value_lamports || ':' || taken_by from real_risk_day"
                                + " where bot_wallet_id = :w and day = :d")
                        .param("w", setup.botWalletId())
                        .param("d", today)
                        .query(String.class)
                        .single())
                .as("the base keeps the cost of the position whose quote fell")
                .isEqualTo((balance + POSITION_COST) + ":DAY_JOB");

        jobs.runDailyLoss();

        assertThat(jdbc.sql("select 'DAILY_LOSS' = any(paused_reasons) from real_preferences where bot_wallet_id = :w")
                        .param("w", setup.botWalletId())
                        .query(Boolean.class)
                        .single())
                .as("the loss since the start of the day pauses the wallet")
                .isTrue();
    }

    @Test
    void withoutTheMarkTheFirstBuyOfTheDayStillTakesThePhoto() {
        Setup setup = realBot("AUTO_FULL");
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        pendingBuy(setup, 50_000_000L);

        assertThat(deferred(setup, today)).isFalse();
        assertThat(jdbc.sql("select taken_by from real_risk_day where bot_wallet_id = :w and day = :d")
                        .param("w", setup.botWalletId())
                        .param("d", today)
                        .query(String.class)
                        .single())
                .isEqualTo("FIRST_EVALUATION");
    }

    /** A settled buy of a bot that is not in the directory, with its fill of {@code mint} in the ledger. */
    private void orderOfABotOutsideTheDirectory(Setup setup, UUID bot, String mint) {
        UUID order = UUID.randomUUID();
        String signature = SyntheticIds.signature();
        Timestamp at = Timestamp.from(clock.instant().minusSeconds(3_600));
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                          side, mint, input_lamports, operation_mode, signing_mode, max_slippage_bps, reason, state,
                          tx_signature, out_base_units, fee_lamports, fee_payer, created_at, updated_at)
                        values (:id, :t, :u, :b, 'BOT', :key, 'BUY', :mint, :lamports, 'AUTO_FULL', 'AUTO_FULL', 100,
                          'teste', 'CONFIRMED', :sig, 1000, 5000, 'BOT_WALLET', :at, :at)""")
                .param("id", order)
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .param("b", bot)
                .param("key", "deferred:" + order)
                .param("mint", mint)
                .param("lamports", POSITION_COST)
                .param("sig", signature)
                .param("at", at)
                .update();
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, mint,
                          token_base_units, tx_signature, reference, idempotency_key, created_at)
                        values (gen_random_uuid(), :t, :w, 'SWAP_FILL_IN', :lamports, :mint, 1000, :sig, :ref, :key,
                          :at)""")
                .param("t", setup.tenantId())
                .param("w", setup.botWalletId())
                .param("lamports", -POSITION_COST)
                .param("mint", mint)
                .param("sig", signature)
                .param("ref", "order:" + order)
                .param("key", order + ":SWAP_FILL_IN")
                .param("at", at)
                .update();
    }

    private boolean deferred(Setup setup, LocalDate day) {
        return jdbc.sql("select count(*) > 0 from real_risk_day_deferred where bot_wallet_id = :w and day = :d")
                .param("w", setup.botWalletId())
                .param("d", day)
                .query(Boolean.class)
                .single();
    }

    private long photos(Setup setup, LocalDate day) {
        return jdbc.sql("select count(*) from real_risk_day where bot_wallet_id = :w and day = :d")
                .param("w", setup.botWalletId())
                .param("d", day)
                .query(Long.class)
                .single();
    }
}
