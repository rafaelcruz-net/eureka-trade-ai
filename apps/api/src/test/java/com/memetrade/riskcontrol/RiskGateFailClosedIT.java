package com.memetrade.riskcontrol;

import static com.memetrade.riskcontrol.RiskGateFixtures.baseLimits;
import static com.memetrade.riskcontrol.RiskGateFixtures.order;
import static com.memetrade.riskcontrol.RiskGateFixtures.writtenForBot;
import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.domain.EvaluationErrors.Cause;
import com.memetrade.riskcontrol.domain.EvaluationRecord;
import com.memetrade.riskcontrol.domain.EvaluationRepository;
import com.memetrade.riskcontrol.domain.OrderGateService;
import com.memetrade.riskcontrol.domain.RejectionEvents;
import com.memetrade.riskcontrol.domain.RejectionRecord;
import com.memetrade.riskcontrol.domain.RejectionRepository;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.CycleAuthContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 9 of U6, WRITTEN BEFORE THE CODE (reliability-design.md; NFR4.6, NFR10.2 to NFR10.5): every technical failure
 * of a reading (C11 price, C11 liquidity, C13) or of a write (evaluation, rejection with its details, trail), injected
 * AFTER the write, and an exceeded {@code lock_timeout}, throw out of the gate: nothing of the evaluation is left, the
 * execution of U7 is never reached, the result is never an approval, and the error is counted by its cause.
 */
class RiskGateFailClosedIT extends AbstractU6IT {

    @Autowired
    private OrderGate gate;

    @Autowired
    private MeterRegistry meters;

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
    void aFailingPriceReadingFailsClosed() {
        assertFailsClosed(
                gates.gate().market(new FailingMarket(market, true, false)).build(), "100.000001", Cause.MARKET_DATA);
    }

    @Test
    void aFailingLiquidityReadingFailsClosed() {
        assertFailsClosed(
                gates.gate().market(new FailingMarket(market, false, true)).build(), "100.000001", Cause.MARKET_DATA);
    }

    @Test
    void aFailingRiskViewFailsClosed() {
        PortfolioRiskView failing = (botId, chain, mint, day) -> {
            throw new IllegalStateException("injected C13 failure");
        };
        assertFailsClosed(gates.gate().portfolio(failing).build(), "100.000001", Cause.PORTFOLIO);
    }

    @Test
    void aFailureAfterWritingTheEvaluationLeavesNothing() {
        EvaluationRepository real = context(EvaluationRepository.class);
        EvaluationRepository failing = new EvaluationRepository() {
            @Override
            public boolean insertIfAbsent(EvaluationRecord evaluation) {
                real.insertIfAbsent(evaluation);
                throw new IllegalStateException("injected failure after the evaluation write");
            }

            @Override
            public void insertAll(List<EvaluationRecord> evaluations) {
                real.insertAll(evaluations);
            }

            @Override
            public Optional<EvaluationRecord> find(UUID tenantId, UUID botId, UUID decisionId) {
                return real.find(tenantId, botId, decisionId);
            }

            @Override
            public boolean existsForPerson(UUID tenantId, UUID ownerUserId, UUID botId) {
                return real.existsForPerson(tenantId, ownerUserId, botId);
            }
        };
        // An approved order too: the failure never turns into an approval.
        assertFailsClosed(gates.gate().evaluations(failing).build(), "100.000000", Cause.DATABASE);
    }

    @Test
    void aFailureAfterWritingTheRejectionAndItsDetailsLeavesNothing() {
        RejectionRepository real = context(RejectionRepository.class);
        RejectionRepository failing = new RejectionRepository() {
            @Override
            public void insert(RejectionRecord rejection) {
                real.insert(rejection);
                throw new IllegalStateException("injected failure after the rejection and detail writes");
            }

            @Override
            public void insertAll(List<RejectionRecord> rejections) {
                real.insertAll(rejections);
            }

            @Override
            public RejectionPage page(UUID tenantId, UUID ownerUserId, UUID botId, Optional<PageKey> after, int limit) {
                return real.page(tenantId, ownerUserId, botId, after, limit);
            }
        };
        assertFailsClosed(gates.gate().rejections(failing).build(), "100.000001", Cause.DATABASE);
    }

    @Test
    void aFailureAfterWritingTheTrailEventLeavesNothing() {
        RejectionEvents real = context(RejectionEvents.class);
        RejectionEvents failing = rejection -> {
            real.rejected(rejection);
            throw new IllegalStateException("injected failure after the outbox write");
        };
        assertFailsClosed(gates.gate().events(failing).build(), "100.000001", Cause.DATABASE);
    }

    /** An exceeded lock_timeout of the cycle transaction is a technical failure too (performance-design.md). */
    @Test
    void anExceededLockTimeoutFailsClosed() throws Exception {
        CandidateOrder order = order(bot, token, OrderSide.BUY, "100.000000");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        double before = errors(Cause.DATABASE);
        try {
            Future<GateResult> holder = other.submit(() -> CycleAuthContext.as(
                    person,
                    () -> cycleTransactions.execute(s -> {
                        GateResult result = gate.evaluate(order, baseLimits());
                        held.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        s.setRollbackOnly();
                        return result;
                    })));
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

            Throwable failure = catchThrowable(() -> inCycle(person, () -> {
                jdbc.sql("set local lock_timeout = '200ms'").update();
                return gate.evaluate(order, baseLimits());
            }));

            assertThat(sqlState(failure)).isEqualTo("55P03");
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            other.shutdownNow();
        }
        assertThat(writtenForBot(jdbc, bot)).isZero();
        assertThat(errors(Cause.DATABASE)).isEqualTo(before + 1);
    }

    private void assertFailsClosed(OrderGateService service, String quantity, Cause cause) {
        CandidateOrder order = order(bot, token, OrderSide.BUY, quantity);
        double before = errors(cause);
        boolean[] executed = {false};

        Throwable failure = catchThrowable(() -> inCycle(person, () -> {
            GateResult result = service.evaluate(order, baseLimits());
            // U5 calls the execution only with Approved; this line is never reached.
            executed[0] = result instanceof GateResult.Approved;
            return result;
        }));

        assertThat(failure).isNotNull();
        assertThat(executed[0]).isFalse();
        assertThat(writtenForBot(jdbc, bot)).isZero();
        assertThat(jdbc.sql("select count(*) from paper_order where bot_id = :b")
                        .param("b", bot)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(errors(cause)).isEqualTo(before + 1);
    }

    private double errors(Cause cause) {
        return meters.get("riskcontrol.evaluation.errors")
                .tag("cause", cause.name())
                .counter()
                .count();
    }

    /** The real bean of a collaborator, to wrap it with an injected failure. */
    private <T> T context(Class<T> type) {
        return applicationContext.getBean(type);
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    /** C11 double that throws on the price and/or the liquidity reading. */
    static final class FailingMarket implements MarketDataQuery {
        private final MarketDataQuery delegate;
        private final boolean failPrice;
        private final boolean failLiquidity;

        FailingMarket(MarketDataQuery delegate, boolean failPrice, boolean failLiquidity) {
            this.delegate = delegate;
            this.failPrice = failPrice;
            this.failLiquidity = failLiquidity;
        }

        @Override
        public MarketValue referencePrice(TokenId token) {
            if (failPrice) {
                throw new IllegalStateException("injected C11 price failure");
            }
            return delegate.referencePrice(token);
        }

        @Override
        public MarketValue liquidity(TokenId token) {
            if (failLiquidity) {
                throw new IllegalStateException("injected C11 liquidity failure");
            }
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
