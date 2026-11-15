package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.riskcontrol.api.BlockedToken;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.HandOverResult;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.riskcontrol.api.UnblockResult;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.shared.error.ApiException;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Step 7 of the U3, written before the code, on real PostgreSQL (BR6.1, BR6.2, BR7.1, BR4.3, BR2.3, U3-NFR4.2 to
 * U3-NFR4.6, U3-NFR4.8, U3-NFR6.1; scenarios S5, S5c, S7, S10, S11, S16, S17; team.md mandatory tests "violação de
 * limites", "pausa do bot impede novas operações", "deduplicação", "isolamento entre contas" and "separação entre REAL e
 * PAPER"). Each test opens the transaction the U4 would open (the cycle, the consumer of the queue or the liquidation).
 */
class TransactionalRealRiskGateIT extends AbstractRealRiskIT {

    @Autowired
    private RealRiskGate gate;

    @Autowired
    private WalletLock lock;

    @Autowired
    private RealPauses pauses;

    @Autowired
    private RealStateQuery states;

    private <T> T tx(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private GateDecision evaluate(OrderIntent intent) {
        return tx(() -> gate.evaluate(intent));
    }

    private OrderIntent buy(Wallet wallet, String mint, long lamports) {
        return OrderIntent.botBuy(
                wallet.tenantId(), wallet.userId(), wallet.botId(), UUID.randomUUID(), mint, lamports);
    }

    private long count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private long evaluations(UUID decisionId) {
        return count("select count(*) from real_risk_evaluation where decision_id = :id", decisionId);
    }

    private long reservations(UUID botWalletId) {
        return count("select count(*) from real_risk_reservation where bot_wallet_id = :id", botWalletId);
    }

    private long rejectionEvents(UUID botId) {
        return count(
                "select count(*) from audit_outbox where payload->>'action' = 'REAL_ORDER_REJECTED'"
                        + " and payload->>'targetRef' = 'bot:' || cast(:id as text)",
                botId);
    }

    /** A wallet of 10 SOL of cap with 20 SOL of REAL balance and a fresh token to buy. */
    private record Ready(Wallet wallet, String mint) {}

    private Ready ready() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 20 * SOL);
        return new Ready(wallet, token("0.01", 6, 5));
    }

    /** A wallet whose positions are worth 9 SOL: 1 SOL of margin under the cap of 10 SOL. */
    private Ready nineSolExposed() {
        Ready ready = ready();
        solAt("200", 5);
        // 0.2 USD / 200 USD x 10^9 / 10^6 = 1 lamport per base unit.
        String held = token("0.2", 6, 5);
        position(ready.wallet(), held, 9 * SOL, 9 * SOL);
        return ready;
    }

    @Test
    void withoutATransactionTheBarrierNeverAnswers() {
        Ready ready = ready();

        assertThatThrownBy(() -> gate.evaluate(buy(ready.wallet(), ready.mint(), SOL / 2)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> gate.evaluate(OrderIntent.userLiquidation(
                        ready.wallet().tenantId(), ready.wallet().userId(), ready.mint(), 10)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> gate.handOver(ready.wallet().tenantId(), UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void anApprovedBuyLeavesAnOpenReservationOfFiveMinutesAndThePhotoOfTheDay() {
        Ready ready = ready();
        OrderIntent intent = buy(ready.wallet(), ready.mint(), SOL / 2);

        GateDecision decision = evaluate(intent);

        assertThat(decision.approved()).isTrue();
        UUID approval = ((GateDecision.Approved) decision).approvalId().orElseThrow();
        assertThat(jdbc.sql("select state, expires_at - created_at = interval '5 minutes' as ttl, bot_wallet_id"
                                + " from real_risk_reservation where approval_id = :id")
                        .param("id", approval)
                        .query()
                        .singleRow())
                .containsEntry("state", "OPEN")
                .containsEntry("ttl", true)
                .containsEntry("bot_wallet_id", ready.wallet().botWalletId());
        assertThat(evaluations(intent.decisionId())).isEqualTo(1);
        assertThat(jdbc.sql("select taken_by from real_risk_day where bot_wallet_id = :id")
                        .param("id", ready.wallet().botWalletId())
                        .query(String.class)
                        .single())
                .isEqualTo(DaySnapshot.TakenBy.FIRST_EVALUATION.name());
    }

    /** S5c: the same decision evaluated twice returns the same answer, with no new buy counted nor reservation. */
    @Test
    void aRepeatedDecisionReturnsTheRecordedAnswerWithoutCountingAgain() {
        Ready ready = ready();
        OrderIntent intent = buy(ready.wallet(), ready.mint(), SOL / 2);

        GateDecision first = evaluate(intent);
        GateDecision again = evaluate(intent);

        assertThat(again).isEqualTo(first);
        assertThat(evaluations(intent.decisionId())).isEqualTo(1);
        assertThat(reservations(ready.wallet().botWalletId())).isEqualTo(1);
    }

    /** BR6.1: a refused decision is never evaluated again, even after the conditions change; no new audit. */
    @Test
    void aRepeatedRefusalStaysRefusedAndIsAuditedOnce() {
        Ready ready = ready();
        assessment.unassessed(ready.mint());
        OrderIntent intent = buy(ready.wallet(), ready.mint(), SOL / 2);

        GateDecision first = evaluate(intent);
        assessment.assessAll();
        GateDecision again = evaluate(intent);

        assertThat(first.approved()).isFalse();
        assertThat(again).isEqualTo(first);
        assertThat(rejectionEvents(ready.wallet().botId())).isEqualTo(1);
    }

    /** S5: two simultaneous buys of 0.6 SOL with 1 SOL of margin: one approved, the other over the cap. */
    @Test
    void twoSimultaneousBuysNeverPassTheCapTogether() {
        Ready ready = nineSolExposed();

        List<Concurrently.Outcome<GateDecision>> outcomes = Concurrently.run(
                () -> evaluate(buy(ready.wallet(), ready.mint(), 600_000_000L)),
                () -> evaluate(buy(ready.wallet(), ready.mint(), 600_000_000L)));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes).filteredOn(outcome -> outcome.value().approved()).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.value().approved())
                .singleElement()
                .satisfies(outcome -> {
                    GateDecision.Rejected refused = (GateDecision.Rejected) outcome.value();
                    assertThat(refused.code()).isEqualTo(RealRejectionCode.REAL_CAP_EXCEEDED);
                    assertThat(refused.detail()).isEqualTo(RealRejectionDetail.CAP);
                });
    }

    /**
     * BR6.1, BR6.2: the consumer of the queue takes over a reservation and records its RESERVE while a cycle evaluates
     * another buy of the same wallet; the first buy is counted exactly once, as a reservation or as a RESERVE.
     */
    @Test
    void theHandOverAndACycleOfTheSameWalletNeverCountABuyTwiceNorMissIt() throws Exception {
        Ready ready = nineSolExposed();
        GateDecision.Approved first = (GateDecision.Approved) evaluate(buy(ready.wallet(), ready.mint(), 600_000_000L));
        UUID approval = first.approvalId().orElseThrow();
        CountDownLatch handedOver = new CountDownLatch(1);
        Thread consumer = new Thread(() -> transactions.executeWithoutResult(status -> {
            assertThat(gate.handOver(ready.wallet().tenantId(), approval)).isEqualTo(HandOverResult.HANDED_OVER);
            ledger.record(new RealEntry(
                    ready.wallet().tenantId(),
                    ready.wallet().botWalletId(),
                    RealEntryKind.RESERVE,
                    600_000_000L,
                    null,
                    "order:" + approval,
                    "order:" + approval + ":reserve"));
            handedOver.countDown();
            pause(Duration.ofMillis(300));
        }));
        consumer.start();
        assertThat(handedOver.await(10, TimeUnit.SECONDS)).isTrue();

        GateDecision second = evaluate(buy(ready.wallet(), ready.mint(), 600_000_000L));
        consumer.join(10_000);

        assertThat(second.approved()).isFalse();
        assertThat(((GateDecision.Rejected) second).figures()).containsEntry("managedLamports", 9_600_000_000L);
    }

    /** U3-NFR4.2: two sells of the same decision at the same time write one evaluation and answer the same. */
    @Test
    void twoSimultaneousSellsOfTheSameDecisionRecordOneEvaluation() {
        Ready ready = ready();
        OrderIntent sell = OrderIntent.botSell(
                ready.wallet().tenantId(),
                ready.wallet().userId(),
                ready.wallet().botId(),
                UUID.randomUUID(),
                ready.mint(),
                1_000);

        List<Concurrently.Outcome<GateDecision>> outcomes =
                Concurrently.run(() -> evaluate(sell), () -> evaluate(sell));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes.get(0).value()).isEqualTo(outcomes.get(1).value());
        assertThat(evaluations(sell.decisionId())).isEqualTo(1);
    }

    /** BR4.3, S3: the loss of 20% refuses the buy, pauses the wallet (audited) and then the sells of the bot stop. */
    @Test
    void aLossOfTheDayPausesTheWalletAndStopsTheBotButNotTheLiquidation() {
        Ready ready = lossOfTwentyPercent();

        GateDecision refused = evaluate(buy(ready.wallet(), ready.mint(), SOL / 2));

        assertThat(((GateDecision.Rejected) refused).detail()).isEqualTo(RealRejectionDetail.DAILY_LOSS);
        assertThat(states.readRealState(
                                ready.wallet().tenantId(), ready.wallet().userId())
                        .orElseThrow()
                        .pausedReasons())
                .containsExactly(RealPauseReason.DAILY_LOSS);
        GateDecision sell = evaluate(OrderIntent.botSell(
                ready.wallet().tenantId(),
                ready.wallet().userId(),
                ready.wallet().botId(),
                UUID.randomUUID(),
                ready.mint(),
                10));
        assertThat(((GateDecision.Rejected) sell).detail()).isEqualTo(RealRejectionDetail.PAUSED_DAILY_LOSS);
        assertThat(evaluate(OrderIntent.userLiquidation(
                                ready.wallet().tenantId(), ready.wallet().userId(), ready.mint(), 10))
                        .approved())
                .isTrue();
    }

    /** U3-NFR4.6: when the cycle rolls back, the decision never existed: no evaluation, no pause, no audit. */
    @Test
    void aRolledBackCycleLeavesNothing() {
        Ready ready = lossOfTwentyPercent();
        OrderIntent intent = buy(ready.wallet(), ready.mint(), SOL / 2);

        transactions.executeWithoutResult(status -> {
            assertThat(gate.evaluate(intent).approved()).isFalse();
            status.setRollbackOnly();
        });

        assertThat(evaluations(intent.decisionId())).isZero();
        assertThat(rejectionEvents(ready.wallet().botId())).isZero();
        assertThat(states.readRealState(
                                ready.wallet().tenantId(), ready.wallet().userId())
                        .orElseThrow()
                        .paused())
                .isFalse();
    }

    /** BR6.3, U3-NFR6.1: the bot of another tenant is refused as nonexistent; S7: a PAPER balance never counts. */
    @Test
    void anotherTenantSeesNoWalletAndWithoutRealBalanceTheBuyIsRefused() {
        Ready ready = ready();
        Wallet stranger = releasedWallet(10 * SOL);
        OrderIntent foreign = OrderIntent.botBuy(
                stranger.tenantId(), ready.wallet().userId(), stranger.botId(), UUID.randomUUID(), ready.mint(), 1);

        assertThat(((GateDecision.Rejected) evaluate(foreign)).detail()).isEqualTo(RealRejectionDetail.NO_BOT_WALLET);
        GateDecision empty = evaluate(buy(stranger, ready.mint(), SOL / 2));
        assertThat(((GateDecision.Rejected) empty).detail()).isEqualTo(RealRejectionDetail.FREE_BALANCE);
    }

    /** S16 and S17: the kill switch stops the liquidation; a pause of the person stops only the bot. */
    @Test
    void theKillSwitchStopsTheLiquidationAndAPauseOfThePersonOnlyTheBot() {
        Ready ready = ready();
        OrderIntent liquidation = OrderIntent.userLiquidation(
                ready.wallet().tenantId(), ready.wallet().userId(), ready.mint(), 10);
        OrderIntent botSell = OrderIntent.botSell(
                ready.wallet().tenantId(),
                ready.wallet().userId(),
                ready.wallet().botId(),
                UUID.randomUUID(),
                ready.mint(),
                10);
        tx(() -> pauses.pauseUser(ready.wallet().tenantId(), ready.wallet().userId(), RealPauseReason.USER));

        assertThat(((GateDecision.Rejected) evaluate(botSell)).detail()).isEqualTo(RealRejectionDetail.PAUSED_USER);
        assertThat(evaluate(liquidation).approved()).isTrue();

        tx(() -> pauses.pauseUser(ready.wallet().tenantId(), ready.wallet().userId(), RealPauseReason.KILL_SWITCH));
        assertThat(((GateDecision.Rejected) evaluate(liquidation)).detail())
                .isEqualTo(RealRejectionDetail.PAUSED_KILL_SWITCH);
    }

    /** S11. */
    @Test
    void aSimulatedSourceRefusesTheBuyAndTheSell() {
        Ready ready = ready();
        market.simulated();

        assertThat(((GateDecision.Rejected) evaluate(buy(ready.wallet(), ready.mint(), SOL / 2))).code())
                .isEqualTo(RealRejectionCode.SIMULATED_SOURCE_ACTIVE);
        assertThat(((GateDecision.Rejected) evaluate(OrderIntent.userLiquidation(
                                ready.wallet().tenantId(), ready.wallet().userId(), ready.mint(), 10)))
                        .code())
                .isEqualTo(RealRejectionCode.SIMULATED_SOURCE_ACTIVE);
    }

    /** BR7.1, M8: the refusal is audited with the mint in its typed field, never in the free details. */
    @Test
    void aRefusalIsAuditedWithTheMintOnlyInItsTypedField() {
        Ready ready = ready();
        assessment.unassessed(ready.mint());

        evaluate(buy(ready.wallet(), ready.mint(), SOL / 2));

        var payload = jdbc.sql("select payload->'real'->>'mint' as mint, (payload->'details')::text as details"
                        + " from audit_outbox where payload->>'action' = 'REAL_ORDER_REJECTED'"
                        + " and payload->>'targetRef' = :target")
                .param("target", "bot:" + ready.wallet().botId())
                .query()
                .singleRow();
        assertThat(payload.get("mint")).isEqualTo(ready.mint());
        assertThat((String) payload.get("details"))
                .contains("TOKEN_NOT_ELIGIBLE")
                .contains("NO_ASSESSMENT")
                .doesNotContain(ready.mint());
    }

    /** U3-NFR4.8: a lock held by another transaction fails the buy after 1 s; the transaction stays usable. */
    @Test
    void aWalletLockedByAnotherTransactionFailsClosedWithoutAbortingTheCycle() throws Exception {
        Ready ready = ready();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactions.executeWithoutResult(status -> {
            lock.acquire(ready.wallet().userId());
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            Integer usable = tx(() -> {
                assertThatThrownBy(() -> gate.evaluate(buy(ready.wallet(), ready.mint(), SOL / 2)))
                        .isInstanceOfSatisfying(RealGateUnavailable.class, failure -> {
                            assertThat(failure.dependency()).isEqualTo(RealGateUnavailable.Dependency.LOCK);
                            assertThat(failure.transactionUsable()).isTrue();
                        });
                return jdbc.sql("select 1").query(Integer.class).single();
            });
            assertThat(usable).isEqualTo(1);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }

    @Test
    void theHandOverClosesTheReservationOnceAndAnExpiredOneNeverBecomesAnOrder() {
        Ready ready = ready();
        UUID first = ((GateDecision.Approved) evaluate(buy(ready.wallet(), ready.mint(), SOL / 2)))
                .approvalId()
                .orElseThrow();
        UUID late = ((GateDecision.Approved) evaluate(buy(ready.wallet(), ready.mint(), SOL / 2)))
                .approvalId()
                .orElseThrow();

        assertThat(tx(() -> gate.handOver(ready.wallet().tenantId(), first))).isEqualTo(HandOverResult.HANDED_OVER);
        assertThat(tx(() -> gate.handOver(ready.wallet().tenantId(), first)))
                .isEqualTo(HandOverResult.ALREADY_HANDED_OVER);
        assertThat(tx(() -> gate.handOver(UUID.randomUUID(), first))).isEqualTo(HandOverResult.NOT_FOUND);

        clock.advance(Duration.ofMinutes(5));
        assertThat(tx(() -> gate.handOver(ready.wallet().tenantId(), late)))
                .isEqualTo(HandOverResult.RESERVATION_EXPIRED);
        assertThat(jdbc.sql("select state from real_risk_reservation where approval_id = :id")
                        .param("id", late)
                        .query(String.class)
                        .single())
                .isEqualTo("EXPIRED");
        assertThat(HandOverResult.RESERVATION_EXPIRED.mayCreateOrder()).isFalse();
    }

    /** S10, BR2.3: the block list stops the buy, never the sell, and only an effective change is audited. */
    @Test
    void theBlockListStopsTheBuyAndIsAuditedOnlyWhenItChanges() {
        Ready ready = ready();
        UUID admin = UUID.randomUUID();

        BlockedToken blocked = gate.blockToken("solana", ready.mint(), "rug pull", admin);
        BlockedToken again = gate.blockToken("solana", ready.mint(), "another reason", admin);

        assertThat(again).isEqualTo(blocked);
        assertThat(((GateDecision.Rejected) evaluate(buy(ready.wallet(), ready.mint(), SOL / 2))).detail())
                .isEqualTo(RealRejectionDetail.BLOCKED);
        assertThat(evaluate(OrderIntent.botSell(
                                ready.wallet().tenantId(),
                                ready.wallet().userId(),
                                ready.wallet().botId(),
                                UUID.randomUUID(),
                                ready.mint(),
                                10))
                        .approved())
                .isTrue();
        assertThat(gate.unblockToken("solana", ready.mint(), admin)).isEqualTo(UnblockResult.UNBLOCKED);
        assertThat(gate.unblockToken("solana", ready.mint(), admin)).isEqualTo(UnblockResult.NOT_BLOCKED);
        assertThat(jdbc.sql("select payload->>'action' from audit_outbox where payload->'real'->>'mint' = :mint"
                                + " and payload->>'action' like 'REAL_TOKEN_%' order by created_at")
                        .param("mint", ready.mint())
                        .query(String.class)
                        .list())
                .containsExactly("REAL_TOKEN_BLOCKED", "REAL_TOKEN_UNBLOCKED");
    }

    @Test
    void anEmptyOrTooLongReasonIsRefused() {
        String mint = SyntheticIds.mint();

        assertThatThrownBy(() -> gate.blockToken("solana", mint, "  ", UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> gate.blockToken("solana", mint, "x".repeat(501), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    /** A wallet that lost 2 SOL of the 10 SOL of its photo: a position bought for 4 SOL now worth 2 SOL. */
    private Ready lossOfTwentyPercent() {
        Wallet wallet = releasedWallet(10 * SOL);
        deposit(wallet, 10 * SOL);
        clock.advance(Duration.ofSeconds(1));
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        transactions.executeWithoutResult(status -> jdbc.sql("""
                        insert into real_risk_day (tenant_id, bot_wallet_id, day, start_value_lamports,
                          start_cap_lamports, taken_at, taken_by)
                        values (:tenant, :wallet, :day, :value, :cap, :at, 'DAY_JOB')""")
                .param("tenant", wallet.tenantId())
                .param("wallet", wallet.botWalletId())
                .param("day", day)
                .param("value", 10 * SOL)
                .param("cap", 10 * SOL)
                .param("at", java.sql.Timestamp.from(clock.instant()))
                .update());
        clock.advance(Duration.ofSeconds(1));
        solAt("200", 5);
        // 0.4 USD / 200 USD x 10^9 / 10^6 = 2 lamports per base unit: 1e9 base units are worth 2 SOL.
        String held = token("0.4", 6, 5);
        position(wallet, held, SOL, 4 * SOL);
        return new Ready(wallet, token("0.01", 6, 5));
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
