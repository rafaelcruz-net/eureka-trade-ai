package com.memetrade.testsupport;

import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Failure injection of the cycle tests (NFR10.2: "falha injetada em cada chamada"): decorators of the REAL
 * {@link OrderGate} (U6), {@link PaperExecution} and {@link LedgerReadModel} (U7) that delegate unchanged until a test
 * arms one of them; then the next call throws a technical failure. They also count the calls, so a test proves that a
 * paused bot never reached U6 nor U7.
 */
public final class U5Faults {

    public enum Point {
        GATE,
        EXECUTION,
        LEDGER
    }

    private final AtomicReference<Point> armed = new AtomicReference<>();
    private final AtomicInteger gateCalls = new AtomicInteger();
    private final AtomicInteger executionCalls = new AtomicInteger();

    public void arm(Point point) {
        armed.set(point);
    }

    public void clear() {
        armed.set(null);
        gateCalls.set(0);
        executionCalls.set(0);
    }

    public int gateCalls() {
        return gateCalls.get();
    }

    public int executionCalls() {
        return executionCalls.get();
    }

    private void failIf(Point point) {
        if (armed.get() == point) {
            throw new org.springframework.dao.DataAccessResourceFailureException("Injected failure at " + point);
        }
    }

    public OrderGate gate(OrderGate real) {
        return new OrderGate() {
            @Override
            public GateResult evaluate(CandidateOrder order, BotLimitsVersionRef limits) {
                gateCalls.incrementAndGet();
                failIf(Point.GATE);
                return real.evaluate(order, limits);
            }
        };
    }

    public PaperExecution execution(PaperExecution real) {
        return new PaperExecution() {
            @Override
            public FundResult fund(UUID botId, BigDecimal amount, String idempotencyKey) {
                return real.fund(botId, amount, idempotencyKey);
            }

            @Override
            public ExecutionResult execute(PaperOrderRequest order, String idempotencyKey) {
                executionCalls.incrementAndGet();
                failIf(Point.EXECUTION);
                return real.execute(order, idempotencyKey);
            }

            @Override
            public CloseResult closePositions(UUID botId, long limitsVersion, String idempotencyKey) {
                return real.closePositions(botId, limitsVersion, idempotencyKey);
            }
        };
    }

    /** The positions read of the cycle fails when LEDGER is armed; every other read is the real one. */
    public LedgerReadModel ledger(LedgerReadModel real) {
        return (LedgerReadModel) java.lang.reflect.Proxy.newProxyInstance(
                LedgerReadModel.class.getClassLoader(),
                new Class<?>[] {LedgerReadModel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("positions")) {
                        failIf(Point.LEDGER);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
