package com.memetrade.bots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.application.BotNotFoundException;
import com.memetrade.bots.application.BotValidationException;
import com.memetrade.bots.application.DraftInput;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotConfigValidator.RawLimits;
import com.memetrade.bots.domain.BotRuleViolation;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.U5Bots;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 9 of U5, written BEFORE BotCommandService (US4.2, US4.3, US4.7, US4.8, US4.9, US4.12, US7.2; BR2.2 to BR2.7,
 * BR5.1; NFR4.2, NFR4.3, NFR10.6, NFR10.7). The commands run against the REAL U7 (fund, closePositions, snapshot) and
 * the real idempotency mechanism of U1, each as the owner bound in the authentication context.
 */
class BotCommandsIT extends AbstractU5WorkerIT {

    @Autowired
    private LedgerReadModel ledger;

    @Autowired
    private PortfolioRiskView risk;

    @Autowired
    private BotAdministration administration;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void createSavesADraftWithTheDefaultsAndOneEvent() {
        Principal person = newPerson();

        UUID botId = fixtures.draft(person);
        Bot bot = fixtures.bot(person, botId);

        assertThat(bot.status()).isEqualTo(BotStatus.of(BotState.DRAFT));
        assertThat(bot.draftStep()).isEqualTo(1);
        assertThat(bot.parameters()).containsEntry("windowMinutes", new BigDecimal("15"));
        assertThat(OutboxEvents.count(jdbc, "BOT_CREATED", "bot:" + botId)).isEqualTo(1);
    }

    @Test
    void eachWizardStepIsSavedAndTheThirdOneValidatesWithLimitsVersionOne() {
        Principal person = newPerson();
        UUID botId = fixtures.draft(person);
        TokenId token = newToken();

        fixtures.as(person, () -> commands.saveDraft(person, botId, U5Bots.step1(token), U5Bots.key()));
        assertThat(fixtures.bot(person, botId).draftStep()).isEqualTo(1);
        fixtures.as(
                person,
                () -> commands.saveDraft(
                        person, botId, U5Bots.step2(U5Bots.defaultLimits(), "1000.000000"), U5Bots.key()));
        assertThat(fixtures.bot(person, botId).draftStep()).isEqualTo(2);
        fixtures.as(person, () -> commands.saveDraft(person, botId, U5Bots.step1(token), U5Bots.key()));
        Bot back = fixtures.bot(person, botId);
        assertThat(back.draftStep()).isEqualTo(2);
        assertThat(back.virtualBalance()).contains(new BigDecimal("1000.000000"));
        fixtures.as(
                person,
                () -> commands.saveDraft(person, botId, new DraftInput(3, null, null, null, null), U5Bots.key()));

        Bot validated = fixtures.bot(person, botId);
        assertThat(validated.status().state()).isEqualTo(BotState.VALIDATED);
        assertThat(validated.currentLimitsVersion()).contains(1);
        assertThat(validated.selection().tokens()).containsExactly(token);
    }

    @Test
    void anInvalidStepIsRefusedPerFieldAndNothingChanges() {
        Principal person = newPerson();
        UUID botId = fixtures.draft(person);
        RawLimits incoherent = new RawLimits("300", "200", "500", "50", "10", "2", "10000", 240L);

        assertThatThrownBy(() -> fixtures.as(
                        person,
                        () -> commands.saveDraft(
                                person, botId, U5Bots.step2(incoherent, "10000.000001"), U5Bots.key())))
                .isInstanceOf(BotValidationException.class)
                .satisfies(failure -> assertThat(((BotValidationException) failure).violations())
                        .extracting(v -> v.field())
                        .contains("limits.maxOrderSize", "virtualBalance"));
        assertThatThrownBy(() -> fixtures.as(
                        person,
                        () -> commands.saveDraft(
                                person, botId, new DraftInput(3, null, null, null, null), U5Bots.key())))
                .isInstanceOf(BotValidationException.class);
        assertThat(fixtures.bot(person, botId).draftStep()).isEqualTo(1);
    }

    @Test
    void startActivatesFundsOnceAndTheSameKeyReplaysWithoutASecondEvent() throws Exception {
        Principal person = newPerson();
        UUID botId = fixtures.validated(person, newToken());
        IdempotencyKey key = U5Bots.key();

        IdempotentResponse first = fixtures.as(person, () -> commands.start(person, botId, key));
        IdempotentResponse again = fixtures.as(person, () -> commands.start(person, botId, key));

        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(again.body())).isEqualTo(json.readTree(first.body()));
        assertThat(fixtures.bot(person, botId).status().state()).isEqualTo(BotState.ACTIVE);
        assertThat(fixtures.bot(person, botId).fundedAt()).isPresent();
        assertThat(ledger.balance(botId)).isEqualByComparingTo("1000");
        assertThat(OutboxEvents.count(jdbc, "BOT_STARTED", "bot:" + botId)).isEqualTo(1);
    }

    /** NFR4.2, AC4.3.3: a double click (same key, in parallel), 50 times: one transition and one event each. */
    @Test
    void aDoubleClickOnStartInParallelActivatesOnceFiftyTimes() {
        for (int i = 0; i < 50; i++) {
            Principal person = newPerson();
            UUID botId = fixtures.validated(person, newToken());
            IdempotencyKey key = U5Bots.key();

            Concurrently.run(
                    () -> fixtures.as(person, () -> commands.start(person, botId, key)),
                    () -> fixtures.as(person, () -> commands.start(person, botId, key)));

            assertThat(fixtures.bot(person, botId).status().state()).isEqualTo(BotState.ACTIVE);
            assertThat(OutboxEvents.count(jdbc, "BOT_STARTED", "bot:" + botId)).isEqualTo(1);
        }
    }

    /** L1: with 2 active the third start is accepted; the fourth is 409 ACTIVE_BOT_LIMIT and stays VALIDATED. */
    @Test
    void l1TheFourthActiveBotIsRefused() {
        Principal person = newPerson();
        for (int i = 0; i < 3; i++) {
            fixtures.active(person, newToken());
        }
        UUID fourth = fixtures.validated(person, newToken());

        assertThatThrownBy(() -> fixtures.as(person, () -> commands.start(person, fourth, U5Bots.key())))
                .isInstanceOf(BotRuleViolation.class)
                .extracting(f -> ((BotRuleViolation) f).code())
                .isEqualTo(BotRuleViolation.Code.ACTIVE_BOT_LIMIT);
        assertThat(fixtures.bot(person, fourth).status().state()).isEqualTo(BotState.VALIDATED);
    }

    /** L2 and NFR4.3: 2 active and two starts in parallel, 50 times: never 4 active, one accepted, one 409. */
    @Test
    void l2TwoStartsInParallelNeverMakeFourActiveFiftyTimes() {
        for (int i = 0; i < 50; i++) {
            Principal person = newPerson();
            fixtures.active(person, newToken());
            fixtures.active(person, newToken());
            UUID third = fixtures.validated(person, newToken());
            UUID fourth = fixtures.validated(person, newToken());

            List<Concurrently.Outcome<IdempotentResponse>> outcomes = Concurrently.run(
                    () -> fixtures.as(person, () -> commands.start(person, third, U5Bots.key())),
                    () -> fixtures.as(person, () -> commands.start(person, fourth, U5Bots.key())));

            assertThat(outcomes).filteredOn(Concurrently.Outcome::failed).hasSize(1);
            assertThat(outcomes.stream()
                            .filter(Concurrently.Outcome::failed)
                            .findFirst()
                            .orElseThrow()
                            .failure())
                    .isInstanceOf(BotRuleViolation.class);
            assertThat(bots.countActive(TenantScope.of(person))).isEqualTo(3);
        }
    }

    /** BR2.3, AC4.8.3: pause adds PERSON once; a second pause with another key writes no second event. */
    @Test
    void pauseIsIdempotentForThePerson() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());

        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));
        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.PAUSED, PauseReason.PERSON));
        assertThat(OutboxEvents.count(jdbc, "BOT_PAUSED", "bot:" + botId)).isEqualTo(1);
    }

    /** AC4.8.4: the resume returns to ACTIVE with the limits in force and writes BOT_RESUMED. */
    @Test
    void resumeReturnsToActive() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key()));

        fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key()));

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.ACTIVE));
        assertThat(OutboxEvents.count(jdbc, "BOT_RESUMED", "bot:" + botId)).isEqualTo(1);
    }

    /**
     * L6 with the REAL U7: after a real loss L of the UTC day, a limit equal to L refuses the resume with the reason and
     * a limit of L plus 0,000001 accepts it; the loss is read through PortfolioRiskView with the owner's principal.
     */
    @Test
    void l6TheResumeAfterTheDailyLossComparesTheNewLimitWithTheRealLossOfTheDay() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        BigDecimal loss = realLoss(person, botId, token);
        forceReasons(botId, "DAILY_LOSS");

        fixtures.as(person, () -> commands.changeLimits(person, botId, withDailyLoss(loss), U5Bots.key()));
        assertThatThrownBy(() -> fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key())))
                .isInstanceOf(BotRuleViolation.class)
                .extracting(f -> ((BotRuleViolation) f).code())
                .isEqualTo(BotRuleViolation.Code.DAILY_LOSS_LIMIT_REACHED);

        fixtures.as(
                person,
                () -> commands.changeLimits(
                        person, botId, withDailyLoss(loss.add(new BigDecimal("0.000001"))), U5Bots.key()));
        fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key()));

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.ACTIVE));
        assertThat(fixtures.bot(person, botId).currentLimitsVersion()).contains(3);
    }

    /** BR2.4: a bot without an explicit token and without position resumes through the neutral token. */
    @Test
    void theResumeOfARulesOnlyBotUsesTheNeutralToken() {
        Principal person = newPerson();
        UUID botId = fixtures.activeWithRulesOnly(person);
        forceReasons(botId, "DAILY_LOSS");

        fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key()));

        assertThat(fixtures.bot(person, botId).status().state()).isEqualTo(BotState.ACTIVE);
    }

    /** L8: with the kill switch on, start and resume are PAUSED_BY_SYSTEM; after it is off, the resume works. */
    @Test
    void l8StartAndResumeWaitForTheKillSwitch() {
        Principal person = newPerson();
        UUID validated = fixtures.validated(person, newToken());
        UUID active = fixtures.active(person, newToken());
        administration.applySystemPause("test-l8-on");
        try {
            assertThatThrownBy(() -> fixtures.as(person, () -> commands.start(person, validated, U5Bots.key())))
                    .extracting(f -> ((BotRuleViolation) f).code())
                    .isEqualTo(BotRuleViolation.Code.PAUSED_BY_SYSTEM);
            assertThatThrownBy(() -> fixtures.as(person, () -> commands.resume(person, active, U5Bots.key())))
                    .extracting(f -> ((BotRuleViolation) f).code())
                    .isEqualTo(BotRuleViolation.Code.PAUSED_BY_SYSTEM);
        } finally {
            administration.liftSystemPause("test-l8-off");
        }

        fixtures.as(person, () -> commands.resume(person, active, U5Bots.key()));
        assertThat(fixtures.bot(person, active).status()).isEqualTo(BotStatus.of(BotState.ACTIVE));
    }

    /** BR2.7: without a position, stop closes the account and the bot is STOPPED. */
    @Test
    void stopWithoutPositionsStopsTheBot() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());

        fixtures.as(person, () -> commands.stop(person, botId, U5Bots.key()));

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.STOPPED));
        assertThat(ledger.account(botId).orElseThrow().status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(OutboxEvents.count(jdbc, "BOT_STOPPED", "bot:" + botId)).isEqualTo(1);
    }

    /** BR2.7, AC4.12.3: with a position and no reliable price, stop leaves the bot CLOSING_PENDING. */
    @Test
    void stopWithAPositionAndNoReliablePriceIsClosingPending() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        buy(person, botId, token, "1.00", "50");
        market.values().price(token, com.memetrade.testsupport.ControllableMarketData.unknown());

        fixtures.as(person, () -> commands.stop(person, botId, U5Bots.key()));

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.CLOSING_PENDING));
        assertThat(OutboxEvents.count(jdbc, "BOT_CLOSING_PENDING", "bot:" + botId))
                .isEqualTo(1);
        assertThatThrownBy(() -> fixtures.as(person, () -> commands.resume(person, botId, U5Bots.key())))
                .isInstanceOf(BotRuleViolation.class);
    }

    /** BR5.1: a new limits version for an ACTIVE bot; incoherent limits refused; a DRAFT is INVALID_TRANSITION. */
    @Test
    void theLimitsGetANewVersionOnlyWhenCoherentAndOnlyOnActiveOrPausedBots() {
        Principal person = newPerson();
        UUID active = fixtures.active(person, newToken());
        UUID draft = fixtures.draft(person);

        fixtures.as(person, () -> commands.changeLimits(person, active, U5Bots.wideLimits(), U5Bots.key()));
        assertThat(fixtures.bot(person, active).currentLimitsVersion()).contains(2);
        assertThat(OutboxEvents.count(jdbc, "BOT_LIMITS_CHANGED", "bot:" + active))
                .isEqualTo(1);
        assertThatThrownBy(() -> fixtures.as(
                        person,
                        () -> commands.changeLimits(
                                person, active, new RawLimits("1", "2", "1", "1", "1", "1", "0", 1L), U5Bots.key())))
                .isInstanceOf(BotValidationException.class);
        assertThatThrownBy(() -> fixtures.as(
                        person, () -> commands.changeLimits(person, draft, U5Bots.wideLimits(), U5Bots.key())))
                .isInstanceOf(BotRuleViolation.class);
    }

    /** BR2.10, NFR5.1: another person's bot, in the same or another tenant, is not found and nothing changes. */
    @Test
    void anotherPersonsBotIsNotFound() {
        Principal owner = newPerson();
        UUID botId = fixtures.active(owner, newToken());
        Principal sameTenant = samePersonTenant(owner);
        Principal stranger = newPerson();

        for (Principal intruder : List.of(sameTenant, stranger)) {
            assertThatThrownBy(() -> fixtures.as(intruder, () -> commands.pause(intruder, botId, U5Bots.key())))
                    .isInstanceOf(BotNotFoundException.class);
            assertThatThrownBy(() -> fixtures.as(intruder, () -> commands.stop(intruder, botId, U5Bots.key())))
                    .isInstanceOf(BotNotFoundException.class);
        }
        assertThat(fixtures.bot(owner, botId).status().state()).isEqualTo(BotState.ACTIVE);
    }

    /** NFR10.7: a lock held for 3 s makes the command fail after its 2 s lock_timeout, never wait forever. */
    @Test
    void aCommandGivesUpAfterTheLockTimeout() throws Exception {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        try (Connection holder = DriverManager.getConnection(
                com.memetrade.testsupport.U5Sql.url(),
                com.memetrade.testsupport.U5Sql.APP_USER,
                com.memetrade.testsupport.U5Sql.APP_PASSWORD)) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("select 1 from bot where bot_id = '" + botId + "' for update");
            long started = System.nanoTime();

            assertThatThrownBy(() -> fixtures.as(person, () -> commands.pause(person, botId, U5Bots.key())))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(3));
            holder.rollback();
        }
    }

    // ----- helpers -----

    private RawLimits withDailyLoss(BigDecimal loss) {
        RawLimits wide = U5Bots.wideLimits();
        return new RawLimits(
                wide.maxOrderSize(),
                wide.maxTokenExposure(),
                wide.maxTotalExposure(),
                loss.setScale(6, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                wide.maxDrawdown(),
                wide.maxSlippage(),
                wide.minLiquidity(),
                wide.minIntervalSeconds());
    }

    /** A real loss of the UTC day through U7: buy 50 at 1,00 then sell 50 at 0,50, in the owner's cycle transaction. */
    private BigDecimal realLoss(Principal person, UUID botId, TokenId token) {
        buy(person, botId, token, "1.00", "50");
        market.values().set(token, "0.50", "100000000", 6);
        fixtures.as(person, () -> new TransactionTemplate(transactionManager).execute(status -> paper().execute(
                        new com.memetrade.ledger.api.PaperExecution.PaperOrderRequest(
                                botId,
                                UUID.randomUUID(),
                                com.memetrade.ledger.api.PaperEnums.OrderSide.SELL,
                                token.chain(),
                                token.mint(),
                                new BigDecimal("50"),
                                1),
                        "test-sell:" + UUID.randomUUID())));
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        BigDecimal loss = fixtures.as(person, () -> new TransactionTemplate(transactionManager)
                .execute(status ->
                        risk.snapshot(botId, token.chain(), token.mint(), day).realizedDayLossWithFees()));
        assertThat(loss).isPositive();
        return loss;
    }

    private void buy(Principal person, UUID botId, TokenId token, String price, String quantity) {
        market.values().set(token, price, "100000000", 6);
        fixtures.as(person, () -> new TransactionTemplate(transactionManager).execute(status -> paper().execute(
                        new com.memetrade.ledger.api.PaperExecution.PaperOrderRequest(
                                botId,
                                UUID.randomUUID(),
                                com.memetrade.ledger.api.PaperEnums.OrderSide.BUY,
                                token.chain(),
                                token.mint(),
                                new BigDecimal(quantity),
                                1),
                        "test-buy:" + UUID.randomUUID())));
    }

    @Autowired
    private com.memetrade.ledger.api.PaperExecution paperExecution;

    private com.memetrade.ledger.api.PaperExecution paper() {
        return paperExecution;
    }

    private void forceReasons(UUID botId, String reasons) {
        jdbc.sql("update bot set state = 'PAUSED', pause_reasons = :reasons where bot_id = :bot")
                .param("reasons", reasons)
                .param("bot", botId)
                .update();
    }
}
