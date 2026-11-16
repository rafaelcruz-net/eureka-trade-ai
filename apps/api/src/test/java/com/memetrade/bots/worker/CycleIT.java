package com.memetrade.bots.worker;

import static com.memetrade.testsupport.U5Cycles.buySignal;
import static com.memetrade.testsupport.U5Cycles.cycleStatus;
import static com.memetrade.testsupport.U5Cycles.cycles;
import static com.memetrade.testsupport.U5Cycles.decisions;
import static com.memetrade.testsupport.U5Cycles.evaluations;
import static com.memetrade.testsupport.U5Cycles.flat;
import static com.memetrade.testsupport.U5Cycles.orders;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.U5Bots;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 10 of U5, written BEFORE the cycle (US4.5 to US4.8; BR3.1 to BR3.7; NFR4.4, NFR4.7, NFR5.2, NFR6.1, NFR10.3):
 * the cycle with the REAL barrier of U6 and the REAL execution of U7, the clock under control and only C11, C12 and the
 * scanner scripted.
 */
class CycleIT extends AbstractU5WorkerIT {

    private static final Instant SLOT = Instant.parse("2026-10-28T12:05:00Z");

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private SlotDispatcher dispatcher;

    @Autowired
    private LedgerReadModel ledger;

    @Autowired
    private PaperExecution paperExecution;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext context;

    /** BR3.4, AC4.5.1, AC4.6.1: a BUY passes the barrier, is executed once and opens the holding. */
    @Test
    void aBuySignalPassesTheBarrierAndIsExecutedOnceForTheOwner() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);

        CycleResult result = cycles.run(ref(person, botId), SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(result.outcome()).contains("FILLED");
        assertThat(decisions(jdbc, botId)).isEqualTo(1);
        assertThat(evaluations(jdbc, botId)).isEqualTo(1);
        assertThat(ledger.positions(botId))
                .anyMatch(
                        p -> p.tokenMint().equals(token.mint()) && p.quantity().signum() > 0);
        assertThat(bots.holdings(botId)).containsKey(token);
        assertThat(jdbc.sql("select action from decision where bot_id = :bot")
                        .param("bot", botId)
                        .query(String.class)
                        .single())
                .isEqualTo("BUY");
    }

    /** BR3.2, L10, AC4.5.4: a slot is processed once; after a restart the same slot is never processed again. */
    @Test
    void l10ASlotIsProcessedAtMostOnceAndNeverAgainAfterARestart() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);

        cycles.run(ref(person, botId), SLOT);
        CycleResult again = cycles.run(ref(person, botId), SLOT);

        assertThat(again.kind()).isEqualTo(CycleResult.Kind.ALREADY_PROCESSED);
        assertThat(cycles(jdbc, botId)).isEqualTo(1);
        assertThat(decisions(jdbc, botId)).isEqualTo(1);
        assertThat(orders(jdbc, botId)).isEqualTo(1);
    }

    /** BR3.2, NFR10.3: a slot that started more than 5 minutes ago is SKIPPED/SLOT_EXPIRED, never decided late. */
    @Test
    void anExpiredSlotIsSkippedAndNeverDecidedLate() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token);
        clock.set(SLOT.plus(Duration.ofMinutes(5)).plusSeconds(1));
        buySignal(market, assessments, token, SLOT);

        CycleResult result = cycles.run(ref(person, botId), SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.SKIPPED);
        assertThat(cycleStatus(jdbc, botId, SLOT)).isEqualTo("SKIPPED:SLOT_EXPIRED");
        assertThat(decisions(jdbc, botId)).isZero();
    }

    /** L7, NFR4.4, AC4.8.2: a pause recorded before the cycle lock: SKIPPED, no call to U6 nor U7, not even exits. */
    @Test
    void l7APauseRecordedBeforeTheCycleSkipsItWithoutCallingU6NorU7() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);

        CycleResult result = cycles.run(ref(person, botId), SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.SKIPPED);
        assertThat(cycleStatus(jdbc, botId, SLOT)).isEqualTo("SKIPPED:PAUSED");
        assertThat(faults.gateCalls()).isZero();
        assertThat(faults.executionCalls()).isZero();
        assertThat(decisions(jdbc, botId)).isZero();
    }

    /** BR3.3 (defence), AC7.2.2: with the kill switch recorded, a bot not yet marked SYSTEM is skipped too. */
    @Test
    void theRecordedKillSwitchSkipsTheCycleEvenBeforeTheBatches() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        jdbc.sql("update system_pause_state set active = true").update();
        try {
            CycleResult result = cycles.run(ref(person, botId), SLOT);

            assertThat(result.kind()).isEqualTo(CycleResult.Kind.SKIPPED);
            assertThat(faults.gateCalls()).isZero();
        } finally {
            jdbc.sql("update system_pause_state set active = false").update();
        }
    }

    @Test
    void aBotThatIsNotActiveIsSkipped() {
        Principal person = newPerson();
        UUID botId = fixtures.validated(person, newToken());
        atCycleOf(SLOT);

        assertThat(cycles.run(ref(person, botId), SLOT).kind()).isEqualTo(CycleResult.Kind.SKIPPED);
        assertThat(cycleStatus(jdbc, botId, SLOT)).isEqualTo("SKIPPED:NOT_ACTIVE");
    }

    /** BR3.4, AC4.7.1: a trigger of the barrier pauses the bot in the same transaction, actor system. */
    @Test
    void theDailyLossTriggerOfTheBarrierPausesTheBot() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, tinyDailyLoss());
        loseInTheDay(person, botId, token);
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);

        CycleResult result = cycles.run(ref(person, botId), SLOT);

        assertThat(result.outcome()).contains("REJECTED");
        assertThat(fixtures.bot(person, botId).status())
                .isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.DAILY_LOSS));
        assertThat(OutboxEvents.payloads(jdbc, "BOT_PAUSED"))
                .anyMatch(payload -> payload.contains(botId.toString()) && payload.contains("DAILY_LOSS"));
    }

    /** L14, BR3.7: a partial SELL keeps the opening of the position; the holding goes only when the position is zero. */
    @Test
    void l14APartialSellKeepsTheOpeningUntilThePositionIsZero() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        cycles.run(ref(person, botId), SLOT);
        Instant opened = bots.holdings(botId).get(token);

        Instant second = SLOT.plus(Duration.ofMinutes(5));
        atCycleOf(second);
        flat(market, assessments, token, second, "1.20", "400");
        CycleResult partial = cycles.run(ref(person, botId), second);

        assertThat(partial.outcome()).contains("FILLED");
        assertThat(bots.holdings(botId)).containsEntry(token, opened);
        Instant third = second.plus(Duration.ofMinutes(5));
        atCycleOf(third);
        flat(market, assessments, token, third, "1.20", "100000000");
        cycles.run(ref(person, botId), third);

        assertThat(bots.holdings(botId)).doesNotContainKey(token);
        assertThat(ledger.positions(botId)).noneMatch(p -> p.quantity().signum() > 0);
    }

    /** NFR4.7: two dispatches of the same slot in parallel, 50 slots: one decision and at most one order per slot. */
    @Test
    void twoDispatchesOfTheSameSlotInParallelGiveOneDecisionFiftyTimes() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        for (int i = 0; i < 50; i++) {
            Instant slot = SLOT.plus(Duration.ofMinutes(5L * i));
            atCycleOf(slot);
            flat(market, assessments, token, slot, "1.00", "100000000");

            Concurrently.run(() -> cycles.run(ref(person, botId), slot), () -> cycles.run(ref(person, botId), slot));

            assertThat(cycles(jdbc, botId)).isEqualTo(i + 1L);
        }
        assertThat(decisions(jdbc, botId)).isEqualTo(50);
        assertThat(orders(jdbc, botId)).isLessThanOrEqualTo(50);
    }

    /** NFR5.2: two tenants in the same slot; each cycle runs as its own owner and writes only its own rows. */
    @Test
    void twoTenantsInTheSameSlotNeverMix() {
        Principal first = newPerson();
        Principal second = newPerson();
        TokenId token = newToken();
        UUID firstBot = fixtures.active(first, token, U5Bots.wideLimits());
        UUID secondBot = fixtures.active(second, token, U5Bots.wideLimits());
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);

        cycles.run(ref(first, firstBot), SLOT);
        cycles.run(ref(second, secondBot), SLOT);

        for (var pair : java.util.Map.of(first, firstBot, second, secondBot).entrySet()) {
            assertThat(jdbc.sql("select tenant_id from decision where bot_id = :bot")
                            .param("bot", pair.getValue())
                            .query(UUID.class)
                            .single())
                    .isEqualTo(pair.getKey().tenantId());
            assertThat(ledger.account(pair.getValue()).orElseThrow().ownerUserId())
                    .isEqualTo(pair.getKey().userId());
        }
    }

    /** NFR6.1, AC4.3.4: everything is PAPER; no component of signing or sending exists in the worker. */
    @Test
    void realIsOffNothingSignsNorSends() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        atCycleOf(SLOT);
        buySignal(market, assessments, token, SLOT);
        cycles.run(ref(person, botId), SLOT);

        assertThat(jdbc.sql("select count(*) from paper_order where bot_id = :bot and mode <> 'PAPER'")
                        .param("bot", botId)
                        .query(Long.class)
                        .single())
                .isZero();
        // 261113-trading-real-bot: the only "signer" bean is the HTTP client of the separate TxSigner process
        // (TxSignerPort); the monolith itself never signs (BotWalletArchitectureTest) and, with REAL off, no bot
        // wallet exists to sign for (RealOffByDefaultRoutesIT). The PAPER cycle still has no signer nor sender.
        // U5: txSignerCommandService and txSignerCommandJob only resend LOWER_CAP and DEACTIVATE commands (ids and
        // amounts) to the TxSigner and never call signSwap nor signWithdrawal (RealOpsArchitectureTest, U5-NFR1.2).
        java.util.Set<String> notSigners = java.util.Set.of("txSigner", "txSignerCommandService", "txSignerCommandJob");
        assertThat(context.getBeanDefinitionNames())
                .filteredOn(name -> !notSigners.contains(name))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("signer")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("transactionsender"));
        assertThat(context.getBean("txSigner")).isInstanceOf(com.memetrade.botwallet.domain.TxSignerPort.class);
    }

    /** BR3.1: the dispatcher runs every ACTIVE bot of the slot once, behind its lock (a second round is skipped). */
    @Test
    void theDispatcherRunsEveryActiveBotOfTheSlotOnce() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID first = fixtures.active(person, token, U5Bots.wideLimits());
        UUID second = fixtures.active(person, newToken(), U5Bots.wideLimits());
        Instant slot = SLOT.plus(Duration.ofHours(6));
        atCycleOf(slot);
        flat(market, assessments, token, slot, "1.00", "100000000");

        var rounds = Concurrently.run(dispatcher::runSlot, dispatcher::runSlot);

        assertThat(rounds).filteredOn(r -> !r.failed() && r.value().ran()).hasSize(1);
        assertThat(cycleStatus(jdbc, first, slot)).startsWith("COMPLETED");
        assertThat(cycles(jdbc, second)).isEqualTo(1);
    }

    // ----- helpers -----

    private void atCycleOf(Instant slot) {
        clock.set(slot.plusSeconds(60));
    }

    private static BotRef ref(Principal person, UUID botId) {
        return new BotRef(botId, TenantScope.of(person));
    }

    private static com.memetrade.bots.domain.BotConfigValidator.RawLimits tinyDailyLoss() {
        var wide = U5Bots.wideLimits();
        return new com.memetrade.bots.domain.BotConfigValidator.RawLimits(
                wide.maxOrderSize(),
                wide.maxTokenExposure(),
                wide.maxTotalExposure(),
                "0.000001",
                wide.maxDrawdown(),
                wide.maxSlippage(),
                wide.minLiquidity(),
                wide.minIntervalSeconds());
    }

    /** A realized loss of the UTC day through the real U7: buy 50 at 1,00, sell 50 at 0,50. */
    private void loseInTheDay(Principal person, UUID botId, TokenId token) {
        execute(person, botId, token, OrderSide.BUY, "1.00");
        execute(person, botId, token, OrderSide.SELL, "0.50");
    }

    private void execute(Principal person, UUID botId, TokenId token, OrderSide side, String price) {
        market.values().set(token, price, "100000000", 6);
        fixtures.as(person, () -> new TransactionTemplate(transactionManager)
                .execute(status -> paperExecution.execute(
                        new PaperExecution.PaperOrderRequest(
                                botId, UUID.randomUUID(), side, token.chain(), token.mint(), new BigDecimal("50"), 1),
                        "test-" + side + ":" + UUID.randomUUID())));
    }
}
