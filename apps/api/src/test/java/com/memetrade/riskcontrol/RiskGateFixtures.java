package com.memetrade.riskcontrol;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.domain.EvaluationErrors;
import com.memetrade.riskcontrol.domain.EvaluationRepository;
import com.memetrade.riskcontrol.domain.LimitChecks;
import com.memetrade.riskcontrol.domain.OrderGateService;
import com.memetrade.riskcontrol.domain.RejectionEvents;
import com.memetrade.riskcontrol.domain.RejectionRepository;
import com.memetrade.testsupport.OutboxEvents;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared pieces of the U6 integration tests: the limits version 1 of the catalogue, candidate orders with a fresh
 * decision, row counts per decision and per bot, and a gate service built with replaced collaborators (the doubles of
 * C11 and C13, or failing repositories) over the real repositories, trail and clock. Built by {@code AbstractU6IT}
 * from the beans of the context; never a bean itself.
 */
public final class RiskGateFixtures {

    private final MarketDataQuery market;
    private final PortfolioRiskView portfolio;
    private final EvaluationRepository evaluations;
    private final RejectionRepository rejections;
    private final RejectionEvents events;
    private final AuthContext auth;
    private final Clock clock;
    private final LimitChecks checks;
    private final EvaluationErrors errors;

    public RiskGateFixtures(
            MarketDataQuery market,
            PortfolioRiskView portfolio,
            EvaluationRepository evaluations,
            RejectionRepository rejections,
            RejectionEvents events,
            AuthContext auth,
            Clock clock,
            LimitChecks checks,
            EvaluationErrors errors) {
        this.market = market;
        this.portfolio = portfolio;
        this.evaluations = evaluations;
        this.rejections = rejections;
        this.events = events;
        this.auth = auth;
        this.clock = clock;
        this.checks = checks;
        this.errors = errors;
    }

    /** Limits version 1 of the catalogue base (functional-spec.md). */
    public static BotLimitsVersionRef baseLimits() {
        return limits("100.000000");
    }

    public static BotLimitsVersionRef limits(String maxOrderSize) {
        return new BotLimitsVersionRef(
                1,
                new BigDecimal(maxOrderSize),
                new BigDecimal("200.000000"),
                new BigDecimal("500.000000"),
                new BigDecimal("2.0000"),
                new BigDecimal("10000.000000"),
                300,
                new BigDecimal("50.000000"),
                new BigDecimal("10.0000"));
    }

    public static CandidateOrder order(UUID botId, TokenId token, OrderSide side, String quantity) {
        return order(botId, UUID.randomUUID(), token, side, quantity);
    }

    public static CandidateOrder order(UUID botId, UUID decisionId, TokenId token, OrderSide side, String quantity) {
        return new CandidateOrder(botId, decisionId, side, token.chain(), token.mint(), new BigDecimal(quantity));
    }

    /** The risk view of the catalogue base, changed by the caller (a C13 double for boundary values). */
    public static RiskSnapshot baseSnapshot() {
        return new RiskSnapshot(
                new BigDecimal("0.000000"),
                new BigDecimal("0.000000"),
                new BigDecimal("0.000000"),
                java.util.Optional.of(new BigDecimal("1000.000000")),
                new BigDecimal("1000.000000"),
                new BigDecimal("1000.000000"),
                java.util.Optional.empty());
    }

    /** A C13 double that always answers {@code snapshot}, counting the calls. */
    public static final class ScriptedPortfolio implements PortfolioRiskView {
        private final RiskSnapshot snapshot;
        public final AtomicInteger calls = new AtomicInteger();

        public ScriptedPortfolio(RiskSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public RiskSnapshot snapshot(UUID botId, String chain, String tokenMint, LocalDate utcDay) {
            calls.incrementAndGet();
            return snapshot;
        }
    }

    public Builder gate() {
        return new Builder();
    }

    /** A gate service with some collaborators replaced; the rest are the real beans. */
    public final class Builder {
        private MarketDataQuery marketData = market;
        private PortfolioRiskView riskView = portfolio;
        private EvaluationRepository evaluationRepository = evaluations;
        private RejectionRepository rejectionRepository = rejections;
        private RejectionEvents rejectionEvents = events;

        public Builder market(MarketDataQuery value) {
            marketData = value;
            return this;
        }

        public Builder portfolio(PortfolioRiskView value) {
            riskView = value;
            return this;
        }

        public Builder evaluations(EvaluationRepository value) {
            evaluationRepository = value;
            return this;
        }

        public Builder rejections(RejectionRepository value) {
            rejectionRepository = value;
            return this;
        }

        public Builder events(RejectionEvents value) {
            rejectionEvents = value;
            return this;
        }

        public OrderGateService build() {
            return new OrderGateService(
                    marketData,
                    riskView,
                    evaluationRepository,
                    rejectionRepository,
                    rejectionEvents,
                    auth,
                    clock,
                    checks,
                    errors);
        }
    }

    // --- row counts -------------------------------------------------------------------------------------------------

    public static long evaluations(JdbcClient jdbc, UUID decisionId) {
        return jdbc.sql("select count(*) from order_evaluation where decision_id = :d")
                .param("d", decisionId)
                .query(Long.class)
                .single();
    }

    public static long rejections(JdbcClient jdbc, UUID decisionId) {
        return jdbc.sql("select count(*) from order_rejection where decision_id = :d")
                .param("d", decisionId)
                .query(Long.class)
                .single();
    }

    public static long details(JdbcClient jdbc, UUID rejectionId) {
        return jdbc.sql("select count(*) from rejection_reason_detail where rejection_id = :r")
                .param("r", rejectionId)
                .query(Long.class)
                .single();
    }

    public static long rejectedEvents(JdbcClient jdbc, UUID botId) {
        return OutboxEvents.count(jdbc, AuditActions.ORDER_REJECTED, "bot:" + botId);
    }

    public static long writtenForBot(JdbcClient jdbc, UUID botId) {
        return jdbc.sql("""
                        select (select count(*) from order_evaluation where bot_id = :b)
                             + (select count(*) from order_rejection where bot_id = :b)
                             + (select count(*) from audit_outbox
                                where payload->>'action' = 'ORDER_REJECTED' and payload->>'targetRef' = :t)
                        """)
                .param("b", botId)
                .param("t", "bot:" + botId)
                .query(Long.class)
                .single();
    }
}
