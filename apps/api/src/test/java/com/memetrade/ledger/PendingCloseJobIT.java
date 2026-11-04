package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.infra.PendingCloseJob;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.PaperDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Passo 12 (written before the job): the pending close is completed ONCE when the price is reliable again (BR5.3,
 * AC4.12.4), also with two rounds in parallel and after a restart; the event has actor {@code system}; the account is
 * CLOSED only without open positions; an account whose lock is held is skipped in the round (NFR10.3, NFR10.4).
 */
class PendingCloseJobIT extends AbstractU7IT {

    @Autowired
    private PaperExecution execution;

    @Autowired
    private PendingCloseJob job;

    private PaperDriver driver;
    private LedgerFacts facts;
    private Principal person;
    private TokenId token;

    @BeforeEach
    void seed() {
        driver = new PaperDriver(execution, transactions);
        facts = new LedgerFacts(jdbc);
        person = newPerson();
        token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
    }

    /** A bot with a position of 100 on {@code token} left CLOSE_PENDING by a closing without price. */
    private CloseResult.PendingPosition pendingBot() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.price(token, ControllableMarketData.unknown());
        CloseResult.Closed closing =
                (CloseResult.Closed) driver.close(person, bot, UUID.randomUUID().toString());
        return closing.pending().getFirst();
    }

    private UUID botOf(CloseResult.PendingPosition pending) {
        return jdbc.sql("select bot_id from position where position_id = :p")
                .param("p", pending.positionId())
                .query(UUID.class)
                .single();
    }

    @Test
    void withAReliablePriceThePendingCloseIsCompletedOnceBySystem() {
        CloseResult.PendingPosition pending = pendingBot();
        UUID bot = botOf(pending);
        market.set(token, "1.200000", "100000.000000", 6);

        PendingCloseJob.Round round = job.runOnce();

        assertThat(round.completed()).isGreaterThanOrEqualTo(1);
        assertThat(facts.order(pending.orderId()).get("status")).isEqualTo("FILLED");
        assertThat(facts.entriesOf(pending.orderId())).containsExactlyInAnyOrder("CLOSE 120.000000", "FEE -0.360000");
        assertThat(facts.position(bot, token.mint()).get("status")).isEqualTo("CLOSED");
        assertThat(facts.accountStatus(bot)).isEqualTo("CLOSED");
        List<String> completed = jdbc.sql("select payload->>'actor' from audit_outbox"
                        + " where payload->>'action' = :a and payload->>'targetRef' = :t")
                .param("a", AuditActions.POSITION_CLOSE_COMPLETED)
                .param("t", "position:" + pending.positionId())
                .query(String.class)
                .list();
        assertThat(completed).containsExactly("system");
    }

    @Test
    void anUnreliablePriceLeavesThePositionPending() {
        CloseResult.PendingPosition pending = pendingBot();

        job.runOnce();

        assertThat(facts.order(pending.orderId()).get("status")).isEqualTo("CLOSE_PENDING");
        assertThat(facts.accountStatus(botOf(pending))).isEqualTo("CLOSING");
    }

    @Test
    void twoRoundsInParallelAndARestartCompleteItOnlyOnce() {
        CloseResult.PendingPosition pending = pendingBot();
        UUID bot = botOf(pending);
        market.set(token, "1.200000", "100000.000000", 6);

        var outcomes = Concurrently.run(job::runOnce, job::runOnce);
        job.runOnce();

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(facts.fills(bot)).as("the buy and ONE close").isEqualTo(2);
        assertThat(facts.events(AuditActions.POSITION_CLOSE_COMPLETED, "position:" + pending.positionId()))
                .isEqualTo(1);
    }

    @Test
    void twoCompletionsOfTheSamePositionInParallelFillOnce() {
        CloseResult.PendingPosition pending = pendingBot();
        UUID bot = botOf(pending);
        market.set(token, "1.200000", "100000.000000", 6);

        var outcomes = Concurrently.run(
                () -> job.completeOne(bot, pending.positionId()), () -> job.completeOne(bot, pending.positionId()));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes).extracting(Concurrently.Outcome::value).containsExactlyInAnyOrder(true, false);
        assertThat(facts.fills(bot)).isEqualTo(2);
    }

    @Test
    void theAccountIsClosedOnlyWhenNoPositionIsLeftOpen() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        TokenId other = newToken();
        market.set(other, "2.000000", "100000.000000", 6);
        driver.buy(person, bot, token, "100");
        driver.buy(person, bot, other, "10");
        market.price(token, ControllableMarketData.unknown());
        market.price(other, ControllableMarketData.unknown());
        driver.close(person, bot, UUID.randomUUID().toString());
        market.set(token, "1.200000", "100000.000000", 6);

        job.runOnce();

        assertThat(facts.position(bot, token.mint()).get("status")).isEqualTo("CLOSED");
        assertThat(facts.position(bot, other.mint()).get("status")).isEqualTo("CLOSE_PENDING");
        assertThat(facts.accountStatus(bot)).isEqualTo("CLOSING");
    }

    @Test
    void anAccountWhoseLockIsHeldIsSkippedAndCompletedInTheNextRound() throws Exception {
        CloseResult.PendingPosition pending = pendingBot();
        UUID bot = botOf(pending);
        market.set(token, "1.200000", "100000.000000", 6);

        try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP.user(), APP.password())) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select bot_id from paper_account where bot_id = ? for update")) {
                lock.setObject(1, bot);
                lock.executeQuery().close();
            }
            PendingCloseJob.Round round = job.runOnce();
            assertThat(round.skipped()).isGreaterThanOrEqualTo(1);
            assertThat(round.failed()).isZero();
            holder.rollback();
        }
        assertThat(facts.order(pending.orderId()).get("status")).isEqualTo("CLOSE_PENDING");

        job.runOnce();

        assertThat(facts.order(pending.orderId()).get("status")).isEqualTo("FILLED");
    }

    @Test
    void theJobRunsAtStartUpAfterEachCollectionAndEverySixtySeconds() throws Exception {
        assertThat(PendingCloseJob.class
                        .getMethod("onStartup", ApplicationReadyEvent.class)
                        .isAnnotationPresent(EventListener.class))
                .isTrue();
        assertThat(PendingCloseJob.class
                        .getMethod("onCollection", com.memetrade.marketdata.api.MarketCollectionCompleted.class)
                        .isAnnotationPresent(EventListener.class))
                .isTrue();
        assertThat(PendingCloseJob.class
                        .getMethod("fallback")
                        .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class)
                        .fixedDelayString())
                .isEqualTo("${memetrade.ledger.marking-fallback-interval}");
    }
}
