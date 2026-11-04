package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult.Filled;
import com.memetrade.ledger.domain.CompensationPort;
import com.memetrade.ledger.domain.Compensations;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.application.ReconciliationService;
import com.memetrade.pnl.application.ReconciliationService.Divergence;
import com.memetrade.pnl.application.ReconciliationService.DivergenceKind;
import com.memetrade.pnl.application.ReconciliationService.Result;
import com.memetrade.pnl.application.ReconciliationService.Trigger;
import com.memetrade.pnl.infra.ReconciliationJob;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.LedgerRows;
import com.memetrade.testsupport.PaperDriver;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passo 16 (written before the code): the reconciliation of each person (F9, BR8.1, BR8.2, BR8.4, NFR10.6, NFR9.7).
 * A fixture injects each divergence (MISSING_ENTRY, AMOUNT_MISMATCH, ORPHAN_ENTRY, POSITION_MISMATCH,
 * SUMMARY_MISMATCH); a valid compensation never diverges; the last 100 per person are kept; a round with divergences
 * logs a WARN with the counts only.
 */
class ReconciliationIT extends AbstractU7IT {

    @Autowired
    private PaperExecution execution;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private ReconciliationJob job;

    @Autowired
    private CompensationPort compensations;

    private PaperDriver driver;
    private LedgerRows rows;
    private Principal person;
    private TokenId token;
    private UUID bot;
    private Filled buy;

    @BeforeEach
    void seed() {
        driver = new PaperDriver(execution, transactions);
        rows = new LedgerRows(appRole());
        person = newPerson();
        token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
        bot = driver.fundedBot(person, "1000.000000");
        buy = (Filled) driver.buy(person, bot, token, "100");
    }

    private Result check() {
        return reconciliation.check(person.tenantId(), person.userId(), Trigger.ON_DEMAND);
    }

    @Test
    void aLedgerWrittenByTheExecutionIsReconciled() {
        market.set(token, "1.500000", "100000.000000", 6);
        driver.sell(person, bot, token, "40");

        Result result = check();

        assertThat(result.reconciled()).isTrue();
        assertThat(result.divergences()).isEmpty();
        assertThat(result.trigger()).isEqualTo(Trigger.ON_DEMAND);
    }

    /**
     * Regression (found by the full suite): a buy and a sell at the SAME instant were replayed in the order of their
     * random fill ids, so the sell could come first and give a false POSITION_MISMATCH. They replay in insertion order.
     */
    @Test
    void fillsOfTheSameInstantReplayInTheOrderTheyHappened() {
        for (int i = 0; i < 20; i++) {
            UUID other = driver.fundedBot(person, "1000.000000");
            driver.buy(person, other, token, "100");
            driver.sell(person, other, token, "40");
            driver.buy(person, other, token, "7");
        }

        assertThat(check().divergences()).isEmpty();
    }

    @Test
    void aFilledOrderWithoutEntriesIsMissingEntry() {
        UUID order = rows.order(person, bot, "TRADE", "FILLED", null, "k-" + UUID.randomUUID());
        rows.fill(person, bot, order);

        assertThat(check().divergences())
                .extracting(Divergence::kind, Divergence::orderId)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        DivergenceKind.MISSING_ENTRY, java.util.Optional.of(order)));
    }

    @Test
    void anExtraEntryOfAnOrderIsAmountMismatchWithExpectedAndFound() {
        rows.entry(person, bot, "FEE", "-0.000001", buy.orderId().toString(), null);

        Divergence divergence = check().divergences().stream()
                .filter(d -> d.kind() == DivergenceKind.AMOUNT_MISMATCH)
                .findFirst()
                .orElseThrow();
        assertThat(divergence.orderId()).contains(buy.orderId());
        assertThat(divergence.expected()).isEqualByComparingTo("-100.901800");
        assertThat(divergence.found()).isEqualByComparingTo("-100.901801");
    }

    @Test
    void anEntryReferencingNoOrderIsOrphan() {
        UUID orphan = rows.entry(person, bot, "SLIPPAGE", "-1.000000", "nothing-" + UUID.randomUUID(), null);

        assertThat(check().divergences())
                .filteredOn(d -> d.kind() == DivergenceKind.ORPHAN_ENTRY)
                .extracting(Divergence::entryId)
                .containsExactly(java.util.Optional.of(orphan));
    }

    @Test
    void aPositionThatIsNotTheReplayOfItsFillsIsPositionMismatch() {
        appRole()
                .sql("update position set quantity = 99, cost_basis = 100.6 where bot_id = :b")
                .param("b", bot)
                .update();

        Divergence divergence = check().divergences().stream()
                .filter(d -> d.kind() == DivergenceKind.POSITION_MISMATCH)
                .findFirst()
                .orElseThrow();
        assertThat(divergence.expected()).isEqualByComparingTo("100");
        assertThat(divergence.found()).isEqualByComparingTo("99");
        assertThat(divergence.positionId()).isPresent();
    }

    @Test
    void aDailySummaryThatIsNotTheSumOfItsFillsIsSummaryMismatch() {
        appRole()
                .sql("update pnl_daily set realized = realized + 5, fill_count = fill_count + 1 where bot_id = :b")
                .param("b", bot)
                .update();

        Divergence divergence = check().divergences().stream()
                .filter(d -> d.kind() == DivergenceKind.SUMMARY_MISMATCH)
                .findFirst()
                .orElseThrow();
        assertThat(divergence.utcDay()).isPresent();
        assertThat(divergence.expected()).isEqualByComparingTo("0");
        assertThat(divergence.found()).isEqualByComparingTo("5");
    }

    @Test
    void c13AValidCompensationOfAFeeIsReconciledAndASecondOneIsRefused() {
        UUID fee = jdbc.sql("select entry_id from ledger_entry where reference_id = :o and type = 'FEE'")
                .param("o", buy.orderId().toString())
                .query(UUID.class)
                .single();

        transactions.executeWithoutResult(status -> Compensations.compensate(compensations, fee, "C13"));

        assertThat(check().reconciled()).isTrue();
        assertThat(new com.memetrade.testsupport.LedgerFacts(jdbc).balance(bot)).isEqualByComparingTo("899.400000");
        Throwable again = org.assertj.core.api.Assertions.catchThrowable(() ->
                transactions.executeWithoutResult(status -> Compensations.compensate(compensations, fee, "again")));
        assertThat(Compensations.isRefusal(again)).isTrue();
    }

    @Test
    void theLatestCheckIsReadAndOnlyTheLastHundredPerPersonAreKept() {
        for (int i = 0; i < 102; i++) {
            clock.advance(Duration.ofSeconds(1));
            reconciliation.check(person.tenantId(), person.userId(), Trigger.SCHEDULED);
        }

        assertThat(jdbc.sql("select count(*) from reconciliation_check where owner_user_id = :u")
                        .param("u", person.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(100);
        assertThat(reconciliation.latest(person.tenantId(), person.userId()))
                .hasValueSatisfying(latest -> assertThat(latest.checkedAt()).isEqualTo(clock.instant()));
        assertThat(reconciliation.latest(newPerson().tenantId(), person.userId()))
                .isEmpty();
    }

    @Test
    void aRoundWithDivergencesLogsAWarnWithTheCountsOnly() {
        rows.entry(person, bot, "SLIPPAGE", "-1.000000", "nothing-" + UUID.randomUUID(), null);
        Logger logger = (Logger) LoggerFactory.getLogger(ReconciliationJob.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            ReconciliationJob.Round round = job.runOnce();

            assertThat(round.divergentPeople()).isGreaterThanOrEqualTo(1);
            assertThat(captured.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .startsWith("reconciliation found divergences")
                            .contains("ORPHAN_ENTRY")
                            .doesNotContain(person.userId().toString())
                            .doesNotContain(bot.toString()));
        } finally {
            logger.detachAppender(captured);
        }
        assertThat(reconciliation.latest(person.tenantId(), person.userId()))
                .hasValueSatisfying(latest -> assertThat(latest.trigger()).isEqualTo(Trigger.SCHEDULED));
    }

    @Test
    void theCheckReadsOneRepeatableReadSnapshot() throws Exception {
        Transactional transactional = ReconciliationService.class
                .getMethod("check", UUID.class, UUID.class, Trigger.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void anotherPersonsLedgerNeverEntersTheCheck() {
        Principal other = newPerson();
        UUID otherBot = rows.account(other, "OPEN");
        rows.entry(other, otherBot, "SLIPPAGE", "-1.000000", "nothing-" + UUID.randomUUID(), null);

        assertThat(check().reconciled()).isTrue();
        assertThat(reconciliation
                        .check(other.tenantId(), other.userId(), Trigger.ON_DEMAND)
                        .reconciled())
                .isFalse();
    }
}
