package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.FillRecorded;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperEnums.OrderStatus;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult.Filled;
import com.memetrade.ledger.api.PaperExecution.FundResult;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.CycleAuthContext;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.PaperDriver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Passo 10 (written before the code): F1 to F3 of functional-spec.md through {@link PaperExecution} (C15), inside the
 * cycle transaction opened as U5 will open it (BR2.1, BR2.5 to BR2.8, BR3.1 to BR3.4, BR5.1, BR5.2, BR9.1).
 */
@RecordApplicationEvents
class PaperExecutionIT extends AbstractU7IT {

    @Autowired
    private PaperExecution execution;

    @Autowired
    private ApplicationEvents events;

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

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void everyOperationRequiresTheCycleTransaction() {
        UUID bot = UUID.randomUUID();
        CycleAuthContext.run(person, () -> {
            assertThatThrownBy(() -> execution.fund(bot, d("1000"), "k"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> execution.execute(
                            PaperDriver.request(bot, OrderSide.BUY, token, "1", UUID.randomUUID(), 1), "k"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> execution.closePositions(bot, 1, "k"))
                    .isInstanceOf(IllegalTransactionStateException.class);
        });
    }

    @Test
    void f1TheFundingCreatesTheAccountOnceAndIsIdempotent() {
        UUID bot = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        FundResult first = driver.fund(person, bot, "1000.000000", key);
        FundResult replay = driver.fund(person, bot, "1000.000000", key);
        FundResult otherAmount = driver.fund(person, bot, "5.000000", key);
        FundResult otherKey =
                driver.fund(person, bot, "1000.000000", UUID.randomUUID().toString());

        assertThat(first).isInstanceOfSatisfying(FundResult.Funded.class, funded -> {
            assertThat(funded.amount()).isEqualTo(d("1000.000000"));
            assertThat(funded.replayed()).isFalse();
        });
        assertThat(replay).isInstanceOfSatisfying(FundResult.Funded.class, f -> assertThat(f.replayed())
                .isTrue());
        assertThat(otherAmount).isEqualTo(new FundResult.Conflict("DIFFERENT_CONTENT"));
        assertThat(otherKey).isEqualTo(new FundResult.Conflict("ALREADY_FUNDED"));
        assertThat(facts.balance(bot)).isEqualByComparingTo("1000");
        assertThat(facts.entries(bot)).isEqualTo(1);
        assertThat(facts.accountStatus(bot)).isEqualTo("OPEN");
        assertThat(facts.events(AuditActions.PAPER_ACCOUNT_FUNDED, "bot:" + bot))
                .isEqualTo(1);
    }

    @Test
    void f2C1ABuyRecordsOrderFillEntriesPositionSummaryEventAndAudit() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        events.clear();

        Filled filled = (Filled) driver.buy(person, bot, token, "100");

        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.requestedQuantity()).isEqualByComparingTo("100");
        assertThat(filled.fill().fillPrice()).isEqualTo(d("1.006000000000"));
        assertThat(filled.fill().grossAmount()).isEqualTo(d("100.600000"));
        assertThat(filled.fill().fees()).isEqualTo(d("0.301800"));
        assertThat(filled.fill().simulationPolicyVersion()).isEqualTo("v1");
        assertThat(filled.fill().marketSource()).isEqualTo(ControllableMarketData.SOURCE.sourceId());
        assertThat(filled.fill().realized()).isEmpty();
        assertThat(facts.entriesOf(filled.orderId()))
                .containsExactlyInAnyOrder("TRADE_COST -100.000000", "SLIPPAGE -0.600000", "FEE -0.301800");
        assertThat(facts.balance(bot)).isEqualByComparingTo("899.098200");
        Map<String, Object> position = facts.position(bot, token.mint());
        assertThat((BigDecimal) position.get("quantity")).isEqualByComparingTo("100");
        assertThat((BigDecimal) position.get("cost_basis")).isEqualByComparingTo("100.6");
        assertThat(position.get("status")).isEqualTo("OPEN");
        assertThat(facts.daily(bot)).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("fees")).isEqualByComparingTo("0.3018");
            assertThat(row.get("fill_count")).isEqualTo(1);
        });
        assertThat(facts.events(AuditActions.PAPER_ORDER_FILLED, "order:" + filled.orderId()))
                .isEqualTo(1);
        assertThat(events.stream(FillRecorded.class)).singleElement().satisfies(event -> assertThat(event.orderId())
                .isEqualTo(filled.orderId()));
    }

    @Test
    void f2C1TheSellRealizesTheGainAndTheSummaryCountsTwoFills() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.500000", "100000.000000", 6);

        Filled sell = (Filled) driver.sell(person, bot, token, "100");

        assertThat(sell.fill().realized()).contains(d("48.425000"));
        assertThat(facts.entriesOf(sell.orderId()))
                .containsExactlyInAnyOrder("TRADE_PROCEEDS 150.000000", "SLIPPAGE -0.975000", "FEE -0.447075");
        assertThat(facts.balance(bot)).isEqualByComparingTo("1047.676125");
        assertThat(facts.position(bot, token.mint()).get("status")).isEqualTo("CLOSED");
        assertThat(facts.daily(bot)).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("realized")).isEqualByComparingTo("48.425");
            assertThat((BigDecimal) row.get("fees")).isEqualByComparingTo("0.748875");
            assertThat(row.get("fill_count")).isEqualTo(2);
        });
    }

    @Test
    void theSameKeyWithTheSameContentIsDuplicateAndWithOtherContentIsConflictAndNothingIsWritten() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        UUID decision = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var request = PaperDriver.request(bot, OrderSide.BUY, token, "100", decision, 1);

        ExecutionResult first = driver.order(person, request, key);
        long entries = facts.entries(bot);
        long audits = facts.events(AuditActions.PAPER_ORDER_FILLED, "order:" + ((Filled) first).orderId());
        ExecutionResult again = driver.order(person, request, key);
        ExecutionResult other =
                driver.order(person, PaperDriver.request(bot, OrderSide.BUY, token, "101", decision, 1), key);

        assertThat(again).isEqualTo(new ExecutionResult.Duplicate(first));
        assertThat(other).isEqualTo(new ExecutionResult.Conflict(key));
        assertThat(facts.orders(bot)).isEqualTo(1);
        assertThat(facts.entries(bot)).isEqualTo(entries);
        assertThat(facts.events(AuditActions.PAPER_ORDER_FILLED, "order:" + ((Filled) first).orderId()))
                .isEqualTo(audits);
    }

    @Test
    void eachNotFilledIsRecordedWithItsEventAndWithoutEntries() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        TokenId unknown = newToken();
        market.price(unknown, ControllableMarketData.unknown());
        market.liquidity(unknown, market.known("1000"));
        market.decimals(unknown, market.known("6"));

        ExecutionResult result = driver.buy(person, bot, unknown, "10");
        ExecutionResult noPosition = driver.sell(person, bot, token, "10");

        assertThat(result).isInstanceOfSatisfying(ExecutionResult.NotFilled.class, nf -> {
            assertThat(nf.reason()).isEqualTo(NotFilledReason.PRICE_UNAVAILABLE);
            assertThat(nf.orderId()).isPresent();
            assertThat(facts.order(nf.orderId().get()).get("status")).isEqualTo("NOT_FILLED");
            assertThat(facts.events(
                            AuditActions.PAPER_ORDER_NOT_FILLED,
                            "order:" + nf.orderId().get()))
                    .isEqualTo(1);
        });
        assertThat(((ExecutionResult.NotFilled) noPosition).reason()).isEqualTo(NotFilledReason.NO_POSITION);
        assertThat(facts.entries(bot)).as("only the FUNDING").isEqualTo(1);
        assertThat(facts.fills(bot)).isZero();
    }

    @Test
    void c15ABuyAboveTheBalanceIsInsufficientBalanceAndTheBalanceStays() {
        UUID bot = driver.fundedBot(person, "100.000000");

        ExecutionResult result = driver.buy(person, bot, token, "99.600000");

        assertThat(((ExecutionResult.NotFilled) result).reason()).isEqualTo(NotFilledReason.INSUFFICIENT_BALANCE);
        assertThat(facts.balance(bot)).isEqualByComparingTo("100");
        assertThat(facts.entries(bot)).isEqualTo(1);
    }

    @Test
    void aBotOfAnotherPersonIsTenantMismatchWithoutAnOrderAndWithRequestForbidden() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        Principal intruder = samePersonTenant(person);
        long before = facts.eventsWithDetail(AuditActions.REQUEST_FORBIDDEN, "reason", "TENANT_MISMATCH");

        ExecutionResult result = driver.buy(intruder, bot, token, "10");

        assertThat(result).isEqualTo(new ExecutionResult.NotFilled(Optional.empty(), NotFilledReason.TENANT_MISMATCH));
        assertThat(facts.orders(bot)).isZero();
        assertThat(facts.eventsWithDetail(AuditActions.REQUEST_FORBIDDEN, "reason", "TENANT_MISMATCH"))
                .isEqualTo(before + 1);
        assertThat(driver.close(newPerson(), bot, UUID.randomUUID().toString()))
                .isEqualTo(new CloseResult.Refused(NotFilledReason.TENANT_MISMATCH));
        assertThat(facts.accountStatus(bot)).isEqualTo("OPEN");
    }

    @Test
    void aBotWithoutFundingIsATechnicalFailure() {
        assertThatThrownBy(() -> driver.buy(person, UUID.randomUUID(), token, "1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void c5aTheRequestedQuantityIsRecordedAndTheTruncationAloneIsNotPartial() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        market.set(token, "2.000000", "1000000.000000", 6);

        Filled filled = (Filled) driver.buy(person, bot, token, "1.123456789");

        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.requestedQuantity()).isEqualByComparingTo("1.123456789");
        assertThat(filled.filledQuantity()).isEqualByComparingTo("1.123456");
        assertThat((BigDecimal) facts.order(filled.orderId()).get("requested_quantity"))
                .isEqualByComparingTo("1.123456789");
    }

    @Test
    void c12TheClosingFillsAtTheReferencePriceAndClosesTheAccount() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.200000", "100000.000000", 6);

        CloseResult.Closed closed =
                (CloseResult.Closed) driver.close(person, bot, UUID.randomUUID().toString());

        assertThat(closed.accountStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.pending()).isEmpty();
        assertThat(closed.closed()).singleElement().satisfies(c -> {
            assertThat(c.realized()).isEqualTo(d("19.400000"));
            assertThat(facts.entriesOf(c.orderId())).containsExactlyInAnyOrder("CLOSE 120.000000", "FEE -0.360000");
            assertThat(facts.order(c.orderId()).get("kind")).isEqualTo("CLOSE");
            assertThat(facts.order(c.orderId()).get("limits_version")).isEqualTo(3L);
        });
        assertThat(facts.position(bot, token.mint()).get("status")).isEqualTo("CLOSED");
        assertThat(facts.accountStatus(bot)).isEqualTo("CLOSED");
    }

    @Test
    void withoutAReliablePriceThePositionWaitsAndARepeatedCloseNeverDuplicatesTheCloseOrder() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.price(token, ControllableMarketData.unknown());
        String key = UUID.randomUUID().toString();

        CloseResult.Closed first = (CloseResult.Closed) driver.close(person, bot, key);
        CloseResult.Closed replay = (CloseResult.Closed) driver.close(person, bot, key);
        CloseResult.Closed otherKey =
                (CloseResult.Closed) driver.close(person, bot, UUID.randomUUID().toString());

        assertThat(first.accountStatus()).isEqualTo(AccountStatus.CLOSING);
        assertThat(first.pending()).hasSize(1);
        UUID positionId = first.pending().getFirst().positionId();
        assertThat(facts.events(AuditActions.POSITION_CLOSE_PENDING, "position:" + positionId))
                .isEqualTo(1);
        assertThat(facts.position(bot, token.mint()).get("status")).isEqualTo("CLOSE_PENDING");
        assertThat(replay.replayed()).isTrue();
        assertThat(otherKey.pending())
                .extracting(CloseResult.PendingPosition::orderId)
                .containsExactly(first.pending().getFirst().orderId());
        assertThat(facts.orders(bot)).as("one buy and one CLOSE").isEqualTo(2);
    }

    @Test
    void anOrderOfABotInClosingIsAccountClosingAndARepeatStaysDuplicate() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        UUID decision = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var request = PaperDriver.request(bot, OrderSide.BUY, token, "10", decision, 1);
        ExecutionResult first = driver.order(person, request, key);
        driver.close(person, bot, UUID.randomUUID().toString());

        ExecutionResult repeat = driver.order(person, request, key);
        ExecutionResult fresh = driver.buy(person, bot, token, "10");

        assertThat(repeat).isEqualTo(new ExecutionResult.Duplicate(first));
        assertThat(((ExecutionResult.NotFilled) fresh).reason()).isEqualTo(NotFilledReason.ACCOUNT_CLOSING);
    }

    @Test
    void theExecutionSetsTheLockAndStatementTimeoutsOfTheCycle() {
        UUID bot = driver.fundedBot(person, "1000.000000");

        List<String> timeouts = CycleAuthContext.as(
                person,
                () -> transactions.execute(status -> {
                    execution.execute(
                            PaperDriver.request(bot, OrderSide.BUY, token, "1", UUID.randomUUID(), 1), "t-" + bot);
                    return List.of(
                            jdbc.sql("show lock_timeout").query(String.class).single(),
                            jdbc.sql("show statement_timeout")
                                    .query(String.class)
                                    .single());
                }));

        assertThat(timeouts).containsExactly("2s", "5s");
        assertThat(jdbc.sql("show lock_timeout").query(String.class).single())
                .as("SET LOCAL ends with the cycle")
                .isNotEqualTo("2s");
    }

    @Test
    void everyRowOfTheLedgerIsPaper() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");

        assertThat(jdbc.sql("""
                        select count(*) from (
                          select mode from paper_account where bot_id = :b union all
                          select mode from paper_order where bot_id = :b union all
                          select mode from fill where bot_id = :b union all
                          select mode from ledger_entry where bot_id = :b union all
                          select mode from position where bot_id = :b union all
                          select mode from pnl_daily where bot_id = :b) m where m.mode <> 'PAPER'
                        """).param("b", bot).query(Long.class).single()).isZero();
    }
}
