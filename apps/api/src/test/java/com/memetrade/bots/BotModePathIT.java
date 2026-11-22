package com.memetrade.bots;

import static com.memetrade.testsupport.U5Cycles.buySignal;
import static com.memetrade.testsupport.U5Cycles.flat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.application.BotValidationException;
import com.memetrade.bots.application.DraftInput;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.worker.CycleResult;
import com.memetrade.bots.worker.CycleRunner;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.U5Bots;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U1, Step 7, WRITTEN BEFORE THE CODE (BR1.8, BR1.9, NFR7.4; reliability-design.md "Proteção
 * do BR1.9" and the decision of the founder on the REAL {@code DecisionRequest}): the whole path validate, start, one
 * cycle, pause, resume and stop of a REAL bot WITHOUT virtual balance nor limits of its own, and the same path of a
 * PAPER bot with today's behaviour.
 *
 * <ul>
 *   <li>REAL: no limits version is ever written; the decision is recorded with {@code limits_version = 0} (the sentinel
 *       "limits of the account") and {@code maxOrderSize = floor(cap x orderMaxBps / 10000)} lamports in dollars at the
 *       SOL of the cycle, rounded down; without a cap the order is zero and no REAL order is recorded;
 *   <li>an old REAL bot with a limits version recorded (261113) ignores it in the REAL branch;
 *   <li>a REAL draft refuses a virtual balance or limits of its own (422) and a REAL bot refuses {@code changeLimits};
 *   <li>PAPER: one limits version at validation, the day loss read on resume and the positions closed on stop.
 * </ul>
 *
 * The worker context of the U5 runs the real cycle with the market doubles; the REAL is off in it, so the barrier
 * refuses every REAL order (nothing is signed nor sent) and only the decision is under test.
 */
class BotModePathIT extends AbstractU5WorkerIT {

    private static final Instant SLOT = Instant.parse("2026-10-28T12:05:00Z");
    private static final String WSOL = "So11111111111111111111111111111111111111112";
    private static final long SOL = 1_000_000_000L;

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private RealLedger ledger;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void aRealBotValidatesStartsCyclesPausesResumesAndStopsWithoutLimitsOfItsOwn() {
        Principal person = newPerson();
        TokenId token = newToken();
        wallet(person, 5 * SOL, 3 * SOL);
        UUID botId = realDraft(person);

        fixtures.as(person, () -> commands.saveDraft(person, botId, U5Bots.step1(token), U5Bots.key()));
        fixtures.as(
                person,
                () -> commands.saveDraft(person, botId, new DraftInput(2, null, null, null, null), U5Bots.key()));
        fixtures.as(
                person,
                () -> commands.saveDraft(person, botId, new DraftInput(3, null, null, null, null), U5Bots.key()));
        assertThat(state(botId)).isEqualTo("VALIDATED");
        fixtures.as(person, () -> commands.start(person, botId, U5Bots.key()));
        assertThat(state(botId)).isEqualTo("ACTIVE");

        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        solAt("150.00");
        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);

        assertThat(result.kind()).as(result.toString()).isEqualTo(CycleResult.Kind.COMPLETED);
        Map<String, Object> decision = decision(botId);
        assertThat(((Number) decision.get("limits_version")).longValue()).isZero();
        assertThat(decision.get("mode")).isEqualTo("REAL");
        // floor(5 SOL x 1000 / 10000) = 0.5 SOL, at 150.00 USD per SOL = 75.000000 (RealSizing, 6 places, down).
        assertThat(new BigDecimal((String) decision.get("max_order_size"))).isEqualByComparingTo("75.000000");
        if ("BUY".equals(decision.get("action"))) {
            BigDecimal value =
                    new BigDecimal((String) decision.get("requested_quantity")).multiply(new BigDecimal("1.02"));
            assertThat(value).isLessThanOrEqualTo(new BigDecimal("75.000000"));
        }
        assertThat(refusedForOrderSize(botId)).isZero();

        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));
        assertThat(state(botId)).isEqualTo("PAUSED");
        fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key()));
        assertThat(state(botId)).isEqualTo("ACTIVE");
        fixtures.as(person, () -> commands.stop(person, botId, U5Bots.key()));
        assertThat(state(botId)).isEqualTo("STOPPED");
        assertThat(limitsVersions(botId)).isZero();
        assertThat(jdbc.sql("select count(*) from paper_account where bot_id = :b")
                        .param("b", botId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void withoutACapTheOrderOfARealBotIsZeroAndNoRealOrderIsRecorded() {
        Principal person = newPerson();
        TokenId token = newToken();
        wallet(person, 0, 3 * SOL);
        UUID botId = startedRealBot(person, token);

        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        solAt("150.00");
        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);

        assertThat(result.kind()).isNotEqualTo(CycleResult.Kind.FAILED);
        Map<String, Object> decision = decision(botId);
        assertThat(new BigDecimal((String) decision.get("max_order_size"))).isZero();
        assertThat(decision.get("action")).isNotEqualTo("BUY");
        assertThat(realDecisionRequests(botId)).isZero();
    }

    @Test
    void withoutACapTheSellOfAnOpenPositionOfARealBotStillGoesToTheRealStep() {
        // Review R-04 (BR1.9): the cap of the account bounds only the buys; with maxOrderSize = NO_BUY the stop loss of
        // an open position still decides SELL and the order goes to the REAL step (the barrier then decides).
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botWalletId = wallet(person, 0, 3 * SOL);
        UUID botId = startedRealBot(person, token);
        // 100 tokens (6 decimals) bought for 1 SOL: at 150.00 USD per SOL the cost is 1.50 USD per token.
        transactions.executeWithoutResult(status -> ledger.record(RealEntry.swapFill(
                person.tenantId(),
                botWalletId,
                RealEntryKind.SWAP_FILL_IN,
                -SOL,
                token.mint(),
                100_000_000L,
                "order:" + UUID.randomUUID(),
                UUID.randomUUID().toString())));

        atCycleOf(SLOT);
        // 0.50 is far below the stop loss of 5% under the cost: the exit decides SELL.
        flat(market, assessments, token, SLOT, "0.50", "100000000");
        solAt("150.00");
        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);

        assertThat(result.kind()).as(result.toString()).isEqualTo(CycleResult.Kind.COMPLETED);
        Map<String, Object> decision = decision(botId);
        assertThat(new BigDecimal((String) decision.get("max_order_size"))).isZero();
        assertThat(decision.get("action")).isEqualTo("SELL");
        assertThat(result.outcome()).as(result.toString()).hasValueSatisfying(outcome -> assertThat(outcome)
                .startsWith("REAL_"));
    }

    @Test
    void withoutThePriceOfSolTheCycleOfARealBotIsSkippedAndNeverFails() {
        Principal person = newPerson();
        TokenId token = newToken();
        wallet(person, 5 * SOL, 3 * SOL);
        UUID botId = startedRealBot(person, token);

        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);

        assertThat(result.kind()).isNotEqualTo(CycleResult.Kind.FAILED);
        assertThat(realDecisionRequests(botId)).isZero();
    }

    @Test
    void anOldRealBotWithALimitsVersionIgnoresItAndUsesTheCapOfTheAccount() {
        Principal person = newPerson();
        TokenId token = newToken();
        wallet(person, 2 * SOL, 3 * SOL);
        UUID botId = startedRealBot(person, token);
        jdbc.sql("""
                        insert into bot_limits_version (bot_id, version, max_order_size, max_token_exposure,
                            max_total_exposure, max_daily_loss, max_drawdown_pct, max_slippage_pct, min_liquidity,
                            min_interval_seconds, created_at, created_by)
                        values (:bot, 1, 1.000000, 2.000000, 5.000000, 1.000000, 10.0000, 2.0000, 0.000000, 240,
                                now(), :by)""").param("bot", botId).param("by", person.userId()).update();
        jdbc.sql("update bot set current_limits_version = 1 where bot_id = :bot")
                .param("bot", botId)
                .update();

        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        solAt("100.00");
        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        Map<String, Object> decision = decision(botId);
        assertThat(((Number) decision.get("limits_version")).longValue()).isZero();
        // floor(2 SOL x 1000 / 10000) = 0.2 SOL at 100.00 = 20.000000, never the 1.000000 of the old version.
        assertThat(new BigDecimal((String) decision.get("max_order_size"))).isEqualByComparingTo("20.000000");
        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));
        fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key()));
        fixtures.as(person, () -> commands.stop(person, botId, U5Bots.key()));
        assertThat(state(botId)).isEqualTo("STOPPED");
    }

    @Test
    void aRealDraftRefusesAVirtualBalanceOrLimitsOfItsOwnAndARealBotRefusesNewLimits() {
        Principal person = newPerson();
        TokenId token = newToken();
        wallet(person, 5 * SOL, 0);
        UUID botId = realDraft(person);
        fixtures.as(person, () -> commands.saveDraft(person, botId, U5Bots.step1(token), U5Bots.key()));

        assertThatThrownBy(() -> fixtures.as(
                        person,
                        () -> commands.saveDraft(
                                person, botId, U5Bots.step2(U5Bots.defaultLimits(), "1000.000000"), U5Bots.key())))
                .isInstanceOf(BotValidationException.class);
        fixtures.as(
                person,
                () -> commands.saveDraft(person, botId, new DraftInput(3, null, null, null, null), U5Bots.key()));
        assertThatThrownBy(() -> fixtures.as(
                        person, () -> commands.changeLimits(person, botId, U5Bots.defaultLimits(), U5Bots.key())))
                .isInstanceOf(BotValidationException.class)
                .satisfies(failure -> assertThat(((BotValidationException) failure).violations())
                        .anyMatch(v -> v.field().equals("limits")));
        assertThat(limitsVersions(botId)).isZero();
    }

    @Test
    void aPaperBotKeepsTodaysPathWithOneLimitsVersionAndClosesItsPositionsOnStop() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        assertThat(limitsVersions(botId)).isEqualTo(1);

        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);

        assertThat(result.outcome()).contains("FILLED");
        Map<String, Object> decision = decision(botId);
        assertThat(((Number) decision.get("limits_version")).longValue()).isEqualTo(1);
        assertThat(new BigDecimal((String) decision.get("max_order_size"))).isEqualByComparingTo("100.000000");
        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));
        fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key()));
        assertThat(state(botId)).isEqualTo("ACTIVE");
        fixtures.as(person, () -> commands.stop(person, botId, U5Bots.key()));
        assertThat(state(botId)).isIn("STOPPED", "CLOSING_PENDING");
        assertThat(limitsVersions(botId)).isEqualTo(1);
    }

    // ----- helpers -----

    private UUID startedRealBot(Principal person, TokenId token) {
        UUID botId = realDraft(person);
        fixtures.as(person, () -> commands.saveDraft(person, botId, U5Bots.step1(token), U5Bots.key()));
        fixtures.as(
                person,
                () -> commands.saveDraft(person, botId, new DraftInput(3, null, null, null, null), U5Bots.key()));
        fixtures.as(person, () -> commands.start(person, botId, U5Bots.key()));
        return botId;
    }

    /**
     * A REAL DRAFT as the creation writes it: in this worker context the REAL is off, so the eligibility would refuse
     * REAL_DISABLED (BotCreateRealIT covers the creation); the path after the creation is under test here.
     */
    private UUID realDraft(Principal person) {
        UUID botId = UUID.randomUUID();
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          draft_step, created_at, updated_at, market)
                        values (:b, :t, :u, 'robo real', 'MOMENTUM_VOLUME', 'v1', 'DRAFT', 1, now(), now(), 'REAL')""")
                .param("b", botId)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        return botId;
    }

    /** An ACTIVE bot wallet of the person with the cap and a deposit, both in lamports. */
    private UUID wallet(Principal person, long capLamports, long depositLamports) {
        UUID botWalletId = UUID.randomUUID();
        Instant created = clock.instant().minus(Duration.ofDays(2));
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", SyntheticIds.mint())
                .param("main", SyntheticIds.mint())
                .param("at", Timestamp.from(created))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, 'AUTO_FULL', :cap, 30000000, 100000, :at)""")
                .param("id", botWalletId)
                .param("cap", capLamports)
                .param("at", Timestamp.from(created))
                .update();
        if (depositLamports > 0) {
            String key = "dep:" + UUID.randomUUID();
            transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                    person.tenantId(), botWalletId, RealEntryKind.DEPOSIT, depositLamports, null, key, key)));
        }
        return botWalletId;
    }

    private void solAt(String usd) {
        market.values().set(new TokenId("solana", WSOL), usd, "100000000", 9);
    }

    private void atCycleOf(Instant slot) {
        clock.set(slot.plusSeconds(60));
    }

    private String state(UUID botId) {
        return jdbc.sql("select state from bot where bot_id = :b")
                .param("b", botId)
                .query(String.class)
                .single();
    }

    private long limitsVersions(UUID botId) {
        return jdbc.sql("select count(*) from bot_limits_version where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    private long realDecisionRequests(UUID botId) {
        return jdbc.sql("select count(*) from real_decision_request where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    private long refusedForOrderSize(UUID botId) {
        return jdbc.sql(
                        "select count(*) from real_risk_evaluation where bot_id = :b and detail::text like '%ORDER_SIZE%'")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> decision(UUID botId) {
        return jdbc.sql("""
                        select limits_version, mode, action, requested_quantity::text as requested_quantity,
                          context->>'maxOrderSize' as max_order_size
                        from decision where bot_id = :b order by created_at desc limit 1""").param("b", botId).query().singleRow();
    }
}
