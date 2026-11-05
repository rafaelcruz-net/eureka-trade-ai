package com.memetrade.riskcontrol;

import static com.memetrade.riskcontrol.RiskGateFixtures.baseLimits;
import static com.memetrade.riskcontrol.RiskGateFixtures.baseSnapshot;
import static com.memetrade.riskcontrol.RiskGateFixtures.order;
import static com.memetrade.riskcontrol.RiskGateFixtures.rejectedEvents;
import static com.memetrade.riskcontrol.RiskGateFixtures.writtenForBot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.api.PaperExecution.PaperOrderRequest;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.riskcontrol.RiskGateFixtures.ScriptedPortfolio;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.GateResult.Approved;
import com.memetrade.riskcontrol.api.GateResult.Rejected;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.OutboxEvents;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Passo 9 of U6, WRITTEN BEFORE THE CODE (F1, BR1.1, BR1.3, BR3.4, BR3.5; NFR5.2, NFR8.1, NFR9.4): the gate inside
 * the cycle transaction, tenant and person only from the context, one reading of C11 and C13 per call, one
 * ORDER_REJECTED per rejection and none per approval, and the catalogue end to end with the real U7 (and a C13 double
 * only for the boundary values a real ledger cannot reach exactly).
 */
class OrderGateIT extends AbstractU6IT {

    @Autowired
    private OrderGate gate;

    @Autowired
    private ObjectMapper json;

    private Principal person;
    private TokenId token;
    private UUID bot;

    @BeforeEach
    void seed() {
        person = newPerson();
        token = newToken();
        marketNow(token, "1.000000000000", "10000.000000");
        bot = fundedBot(person, "1000.000000");
    }

    @Test
    void theGateRequiresTheCycleTransactionAndWritesNothingWithoutIt() {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000000");

        assertThatThrownBy(() ->
                        com.memetrade.testsupport.CycleAuthContext.as(person, () -> gate.evaluate(order, baseLimits())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(writtenForBot(jdbc, bot)).isZero();
    }

    @Test
    void theBaseOrderIsApprovedAndRecordedWithTheTenantAndPersonOfTheContextOnly() {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000000");

        GateResult result = inCycle(person, () -> gate.evaluate(order, baseLimits()));

        assertThat(result).isEqualTo(new Approved(order));
        Map<String, Object> row = jdbc.sql("select * from order_evaluation where decision_id = :d")
                .param("d", order.decisionId())
                .query()
                .singleRow();
        assertThat(row.get("tenant_id")).isEqualTo(person.tenantId());
        assertThat(row.get("owner_user_id")).isEqualTo(person.userId());
        assertThat(row.get("mode")).isEqualTo("PAPER");
        assertThat(row.get("outcome")).isEqualTo("APPROVED");
        assertThat(row.get("limits_version")).isEqualTo(1L);
        assertThat(((java.sql.Timestamp) row.get("evaluated_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(rejectedEvents(jdbc, bot)).isZero();
        assertThat(RiskGateFixtures.rejections(jdbc, order.decisionId())).isZero();
    }

    @Test
    void aRefusedOrderRecordsTheRejectionItsDetailAndExactlyOneEvent() throws Exception {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000001");

        Rejected rejected = (Rejected) inCycle(person, () -> gate.evaluate(order, baseLimits()));

        assertThat(rejected.reasons()).containsExactly(RejectionReason.ORDER_SIZE);
        assertThat(rejected.limitsVersion()).isEqualTo(1);
        assertThat(rejected.pauseTriggers()).isEmpty();
        Map<String, Object> row = jdbc.sql("select * from order_rejection where rejection_id = :r")
                .param("r", rejected.rejectionId())
                .query()
                .singleRow();
        assertThat(row.get("reasons")).isEqualTo("ORDER_SIZE");
        assertThat(row.get("tenant_id")).isEqualTo(person.tenantId());
        assertThat(row.get("owner_user_id")).isEqualTo(person.userId());
        assertThat(row.get("decision_id")).isEqualTo(order.decisionId());
        assertThat(RiskGateFixtures.details(jdbc, rejected.rejectionId())).isEqualTo(1);
        assertThat(jdbc.sql("select outcome from order_evaluation where decision_id = :d")
                        .param("d", order.decisionId())
                        .query(String.class)
                        .single())
                .isEqualTo("REJECTED");

        assertThat(rejectedEvents(jdbc, bot)).isEqualTo(1);
        JsonNode event = json.readTree(OutboxEvents.payloads(jdbc, AuditActions.ORDER_REJECTED).stream()
                .filter(p -> p.contains(rejected.rejectionId().toString()))
                .findFirst()
                .orElseThrow());
        assertThat(event.path("actor").asText()).isEqualTo("system");
        assertThat(event.path("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(event.path("tenantId").asText()).isEqualTo(person.tenantId().toString());
        JsonNode details = event.path("details");
        assertThat(details.path("rejectionId").asText())
                .isEqualTo(rejected.rejectionId().toString());
        assertThat(details.path("decisionId").asText())
                .isEqualTo(order.decisionId().toString());
        assertThat(details.path("reasons").asText()).isEqualTo("ORDER_SIZE");
        assertThat(details.path("pauseTriggers").asText()).isEmpty();
        assertThat(details.path("limitsVersion").asText()).isEqualTo("1");
        assertThat(details.size()).isEqualTo(5);
    }

    @Test
    void theGateReadsTheMarketAndTheRiskViewExactlyOncePerCall() {
        CountingMarket counting = new CountingMarket(market);
        ScriptedPortfolio portfolio = new ScriptedPortfolio(baseSnapshot());
        var service = gates.gate().market(counting).portfolio(portfolio).build();
        UUID decision = UUID.randomUUID();

        inCycle(person, () -> service.evaluate(order(bot, decision, token, OrderSide.BUY, "100.000001"), baseLimits()));
        assertThat(counting.prices.get()).isEqualTo(1);
        assertThat(counting.liquidities.get()).isEqualTo(1);
        assertThat(portfolio.calls.get()).isEqualTo(1);

        // The duplicate call reads them once too (BR1.3), only to record them.
        inCycle(person, () -> service.evaluate(order(bot, decision, token, OrderSide.BUY, "100.000001"), baseLimits()));
        assertThat(counting.prices.get()).isEqualTo(2);
        assertThat(portfolio.calls.get()).isEqualTo(2);
    }

    // --- the catalogue end to end with the real U7 ---------------------------------------------------------------

    @Test
    void e1E4AndE5WithTheRealRiskView() {
        assertApproved(order(bot, token, OrderSide.BUY, "100.000000"), baseLimits());
        assertRefused(
                order(bot, token, OrderSide.BUY, "100.000001"), baseLimits(), List.of(RejectionReason.ORDER_SIZE));

        UUID bot4 = fundedBot(person, "1000.000000");
        BotLimitsVersionRef wider = RiskGateFixtures.limits("200.000000");
        assertApproved(order(bot4, token, OrderSide.BUY, "150.000000"), wider);
        assertRefused(order(bot4, token, OrderSide.BUY, "150.010000"), wider, List.of(RejectionReason.SLIPPAGE));

        TokenId thin = newToken();
        marketNow(thin, "1.000000000000", "9999.999999");
        assertRefused(
                order(bot, thin, OrderSide.BUY, "100.000000"), baseLimits(), List.of(RejectionReason.MIN_LIQUIDITY));
        TokenId noLiquidity = newToken();
        marketNow(noLiquidity, "1.000000000000", "10000.000000");
        market.liquidity(noLiquidity, ControllableMarketData.unknown());
        assertRefused(
                order(bot, noLiquidity, OrderSide.BUY, "100.000000"),
                baseLimits(),
                List.of(RejectionReason.MIN_LIQUIDITY));
    }

    @Test
    void e6E18AndE19WithTheRealRiskView() {
        TokenId sixty = newToken();
        marketNow(sixty, "1.000000000000", "10000.000000");
        market.price(sixty, observedAgo("1.000000000000", Duration.ofSeconds(60)));
        assertApproved(order(bot, sixty, OrderSide.BUY, "100.000000"), baseLimits());

        TokenId old = newToken();
        marketNow(old, "1.000000000000", "10000.000000");
        market.price(old, observedAgo("1.000000000000", Duration.ofSeconds(61)));
        assertRefused(order(bot, old, OrderSide.BUY, "100.000000"), baseLimits(), List.of(RejectionReason.STALE_DATA));

        TokenId missing = newToken();
        marketNow(missing, "1.000000000000", "10000.000000");
        market.price(missing, ControllableMarketData.unknown());
        Rejected e18 = assertRefused(
                order(bot, missing, OrderSide.BUY, "100.000000"), baseLimits(), List.of(RejectionReason.STALE_DATA));
        assertThat(marketOf(e18).path("orderValue").asText()).isEqualTo("UNKNOWN");
        assertThat(marketOf(e18).path("referencePrice").path("status").asText()).isEqualTo("UNKNOWN");

        UUID poor = fundedBot(person, "50.000000");
        TokenId staleLiquidity = newToken();
        marketNow(staleLiquidity, "1.000000000000", "10000.000000");
        market.liquidity(staleLiquidity, observedAgo("10000.000000", Duration.ofSeconds(61)));
        Rejected e19 = assertRefused(
                order(poor, staleLiquidity, OrderSide.BUY, "100.000000"),
                baseLimits(),
                List.of(RejectionReason.STALE_DATA));
        assertThat(marketOf(e19).path("estimatedSlippagePct").asText()).isEqualTo("UNKNOWN");
        assertThat(marketOf(e19).path("orderValue").asText()).isEqualTo("100.000000");
    }

    @Test
    void e9ThePaperBalanceOfTheRealLedger() {
        UUID exact = fundedBot(person, "101.804500");
        assertApproved(order(exact, token, OrderSide.BUY, "100.000000"), baseLimits());

        UUID shortBy = fundedBot(person, "101.804499");
        assertRefused(
                order(shortBy, token, OrderSide.BUY, "100.000000"),
                baseLimits(),
                List.of(RejectionReason.INSUFFICIENT_PAPER_BALANCE));
    }

    @Test
    void e7TheIntervalAfterARealFillOfU7() {
        CandidateOrder first = order(bot, token, OrderSide.BUY, "10.000000");
        assertApproved(first, baseLimits());
        ExecutionResult filled = inCycle(
                person,
                () -> paperExecution.execute(
                        new PaperOrderRequest(
                                bot,
                                first.decisionId(),
                                PaperEnums.OrderSide.BUY,
                                token.chain(),
                                token.mint(),
                                first.requestedQuantity(),
                                1),
                        UUID.randomUUID().toString()));
        assertThat(filled).isInstanceOf(ExecutionResult.Filled.class);

        clock.advance(Duration.ofSeconds(299));
        marketNow(token, "1.000000000000", "10000.000000");
        assertRefused(order(bot, token, OrderSide.BUY, "10.000000"), baseLimits(), List.of(RejectionReason.INTERVAL));
        clock.advance(Duration.ofSeconds(1));
        marketNow(token, "1.000000000000", "10000.000000");
        assertApproved(order(bot, token, OrderSide.BUY, "10.000000"), baseLimits());
    }

    // --- boundary values through a C13 double over the real repositories and trail -----------------------------

    @Test
    void e2E3E10E11E12E13E15AndE17WithARiskViewDouble() {
        assertBoundary(s -> snapshot(s, "100.000000", "100.000000", null, null), true, List.of(), null);
        assertBoundary(
                s -> snapshot(s, "100.000001", "100.000001", null, null),
                false,
                List.of(RejectionReason.TOKEN_EXPOSURE),
                List.of());
        assertBoundary(s -> snapshot(s, "0.000000", "400.000000", null, null), true, List.of(), null);
        assertBoundary(
                s -> snapshot(s, "0.000000", "400.000001", null, null),
                false,
                List.of(RejectionReason.TOTAL_EXPOSURE),
                List.of());
        assertBoundary(s -> snapshot(s, null, null, "49.999999", null), true, List.of(), null);
        assertBoundary(
                s -> snapshot(s, null, null, "50.000000", null),
                false,
                List.of(RejectionReason.DAILY_LOSS),
                List.of(PauseTrigger.DAILY_LOSS));
        assertBoundary(s -> snapshot(s, null, null, null, Optional.of("903.600000")), true, List.of(), null);
        assertBoundary(
                s -> snapshot(s, null, null, null, Optional.of("903.599999")),
                false,
                List.of(RejectionReason.DRAWDOWN),
                List.of());
        assertBoundary(
                s -> snapshot(s, null, null, null, Optional.of("900.000000")),
                false,
                List.of(RejectionReason.DRAWDOWN),
                List.of(PauseTrigger.DRAWDOWN));
        assertBoundary(
                s -> snapshot(s, null, null, null, Optional.empty()),
                false,
                List.of(RejectionReason.DRAWDOWN),
                List.of());
        assertBoundary(
                s -> snapshot(s, null, null, "50.000000", Optional.of("900.000000")),
                false,
                List.of(RejectionReason.DAILY_LOSS, RejectionReason.DRAWDOWN),
                List.of(PauseTrigger.DAILY_LOSS, PauseTrigger.DRAWDOWN));
    }

    @Test
    void e16AndE17SellsWithARiskViewDouble() {
        RiskSnapshot sellable = new RiskSnapshot(
                new BigDecimal("200.000000"),
                new BigDecimal("200.000000"),
                new BigDecimal("0.000000"),
                Optional.of(new BigDecimal("1000.000000")),
                new BigDecimal("1000.000000"),
                new BigDecimal("0.000000"),
                Optional.empty());
        var e16 = gates.gate().portfolio(new ScriptedPortfolio(sellable)).build();
        CandidateOrder sell = order(bot, token, OrderSide.SELL, "150.000000");
        assertThat(inCycle(person, () -> e16.evaluate(sell, baseLimits()))).isEqualTo(new Approved(sell));

        RiskSnapshot lost = snapshot(baseSnapshot(), null, null, "50.000000", null);
        var e17 = gates.gate().portfolio(new ScriptedPortfolio(lost)).build();
        GateResult refused =
                inCycle(person, () -> e17.evaluate(order(bot, token, OrderSide.SELL, "100.000000"), baseLimits()));
        assertThat(((Rejected) refused).reasons()).containsExactly(RejectionReason.DAILY_LOSS);
        assertThat(((Rejected) refused).pauseTriggers()).containsExactly(PauseTrigger.DAILY_LOSS);
    }

    // --- tenant of the context ---------------------------------------------------------------------------------

    @Test
    void aBotOfAnotherPersonInTheContextRollsEverythingBack() {
        Principal other = newPerson();
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000000");

        assertThatThrownBy(() -> inCycle(other, () -> gate.evaluate(order, baseLimits())))
                .isInstanceOf(PortfolioRiskView.TenantMismatchException.class);

        // U6 writes nothing (no evaluation, no rejection, no ORDER_REJECTED) and never counts on the event of U7.
        assertThat(writtenForBot(jdbc, bot)).isZero();
        // Observed: U7 writes its single REQUEST_FORBIDDEN in a transaction of its own (PnlAudit), so it survives the
        // rollback of the cycle (functional-spec.md assumed it would vanish; recorded in code-summary.md).
        assertThat(OutboxEvents.count(jdbc, AuditActions.REQUEST_FORBIDDEN, "bot:" + bot))
                .isEqualTo(1);
    }

    @Test
    void aBotWithoutAPaperAccountIsATechnicalFailureAndNothingIsWritten() {
        UUID unfunded = UUID.randomUUID();

        assertThatThrownBy(() ->
                        inCycle(person, () -> gate.evaluate(order(unfunded, token, OrderSide.BUY, "1"), baseLimits())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(writtenForBot(jdbc, unfunded)).isZero();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private void assertApproved(CandidateOrder order, BotLimitsVersionRef limits) {
        long eventsBefore = rejectedEvents(jdbc, order.botId());
        GateResult result = inCycle(person, () -> gate.evaluate(order, limits));
        assertThat(result).as(order.requestedQuantity() + " " + order.side()).isEqualTo(new Approved(order));
        assertThat(RiskGateFixtures.evaluations(jdbc, order.decisionId())).isEqualTo(1);
        assertThat(rejectedEvents(jdbc, order.botId())).isEqualTo(eventsBefore);
    }

    private Rejected assertRefused(CandidateOrder order, BotLimitsVersionRef limits, List<RejectionReason> reasons) {
        long eventsBefore = rejectedEvents(jdbc, order.botId());
        GateResult result = inCycle(person, () -> gate.evaluate(order, limits));
        assertThat(result).isInstanceOf(Rejected.class);
        Rejected rejected = (Rejected) result;
        assertThat(rejected.reasons()).isEqualTo(reasons);
        assertThat(rejected.limitsVersion()).isEqualTo(1);
        assertThat(RiskGateFixtures.details(jdbc, rejected.rejectionId())).isEqualTo(reasons.size());
        assertThat(rejectedEvents(jdbc, order.botId())).isEqualTo(eventsBefore + 1);
        return rejected;
    }

    private void assertBoundary(
            UnaryOperator<RiskSnapshot> change,
            boolean approved,
            List<RejectionReason> reasons,
            List<PauseTrigger> triggers) {
        var service = gates.gate()
                .portfolio(new ScriptedPortfolio(change.apply(baseSnapshot())))
                .build();
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000000");
        long eventsBefore = rejectedEvents(jdbc, bot);

        GateResult result = inCycle(person, () -> service.evaluate(order, baseLimits()));

        if (approved) {
            assertThat(result).isEqualTo(new Approved(order));
            assertThat(rejectedEvents(jdbc, bot)).isEqualTo(eventsBefore);
        } else {
            Rejected rejected = (Rejected) result;
            assertThat(rejected.reasons()).isEqualTo(reasons);
            assertThat(List.copyOf(rejected.pauseTriggers())).isEqualTo(triggers);
            assertThat(RiskGateFixtures.details(jdbc, rejected.rejectionId())).isEqualTo(reasons.size());
            assertThat(rejectedEvents(jdbc, bot)).isEqualTo(eventsBefore + 1);
        }
    }

    private static RiskSnapshot snapshot(
            RiskSnapshot base, String token, String total, String dayLoss, Optional<String> equity) {
        return new RiskSnapshot(
                token == null ? base.tokenExposure() : new BigDecimal(token),
                total == null ? base.totalExposure() : new BigDecimal(total),
                dayLoss == null ? base.realizedDayLossWithFees() : new BigDecimal(dayLoss),
                equity == null ? base.equity() : equity.map(BigDecimal::new),
                base.peakEquity(),
                base.availableVirtualBalance(),
                base.lastTradeAt());
    }

    private JsonNode marketOf(Rejected rejected) {
        try {
            return json.readTree(jdbc.sql("select market::text from order_rejection where rejection_id = :r")
                    .param("r", rejected.rejectionId())
                    .query(String.class)
                    .single());
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    /** Counts the C11 reads of price and liquidity, delegating to the market double. */
    static final class CountingMarket implements MarketDataQuery {
        private final MarketDataQuery delegate;
        final AtomicInteger prices = new AtomicInteger();
        final AtomicInteger liquidities = new AtomicInteger();

        CountingMarket(MarketDataQuery delegate) {
            this.delegate = delegate;
        }

        @Override
        public MarketValue referencePrice(TokenId token) {
            prices.incrementAndGet();
            return delegate.referencePrice(token);
        }

        @Override
        public MarketValue liquidity(TokenId token) {
            liquidities.incrementAndGet();
            return delegate.liquidity(token);
        }

        @Override
        public MarketValue decimals(TokenId token) {
            return delegate.decimals(token);
        }

        @Override
        public PriceVolumeWindow window(TokenId token, Duration length) {
            return delegate.window(token, length);
        }

        @Override
        public List<SourceStatus> sourceStatuses() {
            return delegate.sourceStatuses();
        }
    }
}
