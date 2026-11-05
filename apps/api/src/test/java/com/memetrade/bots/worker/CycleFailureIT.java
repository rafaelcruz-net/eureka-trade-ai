package com.memetrade.bots.worker;

import static com.memetrade.testsupport.U5Cycles.buySignal;
import static com.memetrade.testsupport.U5Cycles.cycleStatus;
import static com.memetrade.testsupport.U5Cycles.decisions;
import static com.memetrade.testsupport.U5Cycles.evaluations;
import static com.memetrade.testsupport.U5Cycles.orders;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.U5Bots;
import com.memetrade.testsupport.U5Faults;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 10 of U5, written BEFORE the cycle (BR3.5; NFR9.2, NFR10.2, NFR2.4): a technical failure at each call (C11,
 * the barrier of U6, the read and the execution of U7) rolls the whole cycle back; the FAILED slot is recorded AFTER
 * the rollback in a transaction of its own, quickly and without a lock left behind; the slot is not repeated and the
 * next slot decides again.
 */
class CycleFailureIT extends AbstractU5WorkerIT {

    private static final Instant SLOT = Instant.parse("2026-10-28T13:05:00Z");

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private MeterRegistry meters;

    @Test
    void aFailureOfTheMarketDataIsRecordedAsFailedWithNothingElse() {
        Fixture fixture = fixture();
        market.failWith(new IllegalStateException("C11 down"));
        double before = errors("MARKET_DATA");
        long started = System.nanoTime();

        CycleResult result = cycles.run(fixture.ref(), SLOT);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(result.kind()).isEqualTo(CycleResult.Kind.FAILED);
        assertThat(cycleStatus(jdbc, fixture.botId(), SLOT)).isEqualTo("FAILED");
        assertThat(decisions(jdbc, fixture.botId())).isZero();
        assertThat(errors("MARKET_DATA")).isEqualTo(before + 1);
    }

    @Test
    void aFailureOfTheBarrierLeavesNoEvaluationNorDecision() {
        Fixture fixture = fixture();
        faults.arm(U5Faults.Point.GATE);

        CycleResult result = cycles.run(fixture.ref(), SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.FAILED);
        assertThat(evaluations(jdbc, fixture.botId())).isZero();
        assertThat(decisions(jdbc, fixture.botId())).isZero();
        assertThat(jdbc.sql("select failure_cause from bot_cycle where bot_id = :bot")
                        .param("bot", fixture.botId())
                        .query(String.class)
                        .single())
                .isEqualTo("RISK_GATE");
    }

    @Test
    void aFailureOfTheExecutionLeavesNoOrderNorEvaluationNorDecision() {
        Fixture fixture = fixture();
        faults.arm(U5Faults.Point.EXECUTION);

        CycleResult result = cycles.run(fixture.ref(), SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.FAILED);
        assertThat(orders(jdbc, fixture.botId())).isZero();
        assertThat(evaluations(jdbc, fixture.botId())).isZero();
        assertThat(decisions(jdbc, fixture.botId())).isZero();
        assertThat(errors("EXECUTION")).isPositive();
    }

    @Test
    void aFailureOfTheLedgerReadIsFailedAndTheNextSlotDecidesAgain() {
        Fixture fixture = fixture();
        faults.arm(U5Faults.Point.LEDGER);

        assertThat(cycles.run(fixture.ref(), SLOT).kind()).isEqualTo(CycleResult.Kind.FAILED);
        assertThat(cycles.run(fixture.ref(), SLOT).kind()).isEqualTo(CycleResult.Kind.ALREADY_PROCESSED);
        faults.clear();
        Instant next = SLOT.plus(Duration.ofMinutes(5));
        clock.set(next.plusSeconds(60));
        buySignal(market, assessments, fixture.token(), next);

        assertThat(cycles.run(fixture.ref(), next).kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(decisions(jdbc, fixture.botId())).isEqualTo(1);
    }

    /** NFR2.4: four cycles failing at the same time all finish, without waiting for a connection. */
    @Test
    void fourCyclesFailingAtTheSameTimeAllFinishQuickly() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fixtures.add(fixture());
        }
        faults.arm(U5Faults.Point.EXECUTION);
        long started = System.nanoTime();

        @SuppressWarnings("unchecked")
        Callable<CycleResult>[] tasks = fixtures.stream()
                .map(f -> (Callable<CycleResult>) () -> cycles.run(f.ref(), SLOT))
                .toArray(Callable[]::new);
        var outcomes = Concurrently.run(tasks);

        assertThat(outcomes).allMatch(o -> !o.failed() && o.value().kind() == CycleResult.Kind.FAILED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        for (Fixture fixture : fixtures) {
            assertThat(cycleStatus(jdbc, fixture.botId(), SLOT)).isEqualTo("FAILED");
        }
    }

    // ----- helpers -----

    private record Fixture(UUID botId, TokenId token, BotRef ref) {}

    private Fixture fixture() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = this.fixtures.active(person, token, U5Bots.wideLimits());
        clock.set(SLOT.plusSeconds(60));
        buySignal(market, assessments, token, SLOT);
        return new Fixture(botId, token, new BotRef(botId, TenantScope.of(person)));
    }

    private double errors(String cause) {
        var counter = meters.find("bots.cycle.errors").tag("cause", cause).counter();
        return counter == null ? 0 : counter.count();
    }
}
