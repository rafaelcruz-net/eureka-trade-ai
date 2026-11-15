package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 13 of the U3 (U3-NFR3.1 to U3-NFR3.4, performance-requirements.md "Condição de medida"): an INFORMATIVE
 * measurement on PostgreSQL in a container, with 21 wallets of 10 open positions each and 4 evaluations per second. It
 * logs the p95 of the buy and of the sell (targets 150 ms and 30 ms) and the time of the jobs F3 and F4 over the 21
 * wallets (target 5 s). There is NO gate on these numbers (team.md, "Desempenho"); the test fails only when an
 * evaluation fails.
 */
class RealRiskMeasurementIT extends AbstractRealRiskIT {

    private static final Logger log = LoggerFactory.getLogger(RealRiskMeasurementIT.class);

    private static final int WALLETS = 21;
    private static final int POSITIONS = 10;
    private static final int PER_SECOND = 4;
    private static final int EVALUATIONS = 24;

    @Autowired
    private RealRiskGate gate;

    @Autowired
    private RealRiskJobs jobs;

    private record Seeded(List<Wallet> wallets, String target) {}

    private Seeded seed() {
        solAt("200", 5);
        List<Wallet> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            Wallet wallet = releasedWallet(10 * SOL);
            deposit(wallet, 20 * SOL);
            for (int p = 0; p < POSITIONS; p++) {
                // 0.2 USD / 200 USD x 10^9 / 10^6 = 1 lamport per base unit: each position is worth 0.1 SOL.
                position(wallet, token("0.2", 6, 5), 100_000_000L, 100_000_000L);
            }
            wallets.add(wallet);
        }
        return new Seeded(wallets, token("0.01", 6, 5));
    }

    @Test
    void theBuyAndTheSellAreMeasuredAtFourEvaluationsPerSecond() throws Exception {
        Seeded seeded = seed();
        List<OrderIntent> intents = new ArrayList<>();
        for (int i = 0; i < EVALUATIONS; i++) {
            Wallet wallet = seeded.wallets().get(i % WALLETS);
            intents.add(
                    i % 2 == 0
                            ? OrderIntent.botBuy(
                                    wallet.tenantId(),
                                    wallet.userId(),
                                    wallet.botId(),
                                    UUID.randomUUID(),
                                    seeded.target(),
                                    100_000_000L)
                            : OrderIntent.botSell(
                                    wallet.tenantId(),
                                    wallet.userId(),
                                    wallet.botId(),
                                    UUID.randomUUID(),
                                    seeded.target(),
                                    1_000));
        }

        ScheduledExecutorService pacer = Executors.newScheduledThreadPool(PER_SECOND);
        List<Future<Measured>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < intents.size(); i++) {
                OrderIntent intent = intents.get(i);
                futures.add(pacer.schedule(() -> measure(intent), i * (1000L / PER_SECOND), TimeUnit.MILLISECONDS));
            }
            List<Measured> measured = new ArrayList<>();
            for (Future<Measured> future : futures) {
                measured.add(future.get(60, TimeUnit.SECONDS));
            }
            Duration buy = p95(measured, true);
            Duration sell = p95(measured, false);
            log.info(
                    "U3 informative measurement (no gate): wallets={}, positionsPerWallet={}, ratePerSecond={},"
                            + " evaluations={}, buyP95Ms={}, sellP95Ms={}, buyTargetMs=150, sellTargetMs=30",
                    WALLETS,
                    POSITIONS,
                    PER_SECOND,
                    measured.size(),
                    buy.toMillis(),
                    sell.toMillis());
            assertThat(measured).hasSize(EVALUATIONS);
            assertThat(measured)
                    .allSatisfy(m -> assertThat(m.decision().evaluationId()).isNotNull());
        } finally {
            pacer.shutdownNow();
        }
    }

    @Test
    void theJobsOfTheDayAndOfTheLossAreMeasuredOverTheWallets() {
        seed();

        long started = System.nanoTime();
        RealRiskJobs.DayRound day = jobs.runDay();
        Duration dayTook = Duration.ofNanos(System.nanoTime() - started);
        started = System.nanoTime();
        RealRiskJobs.LossRound loss = jobs.runDailyLoss();
        Duration lossTook = Duration.ofNanos(System.nanoTime() - started);

        log.info(
                "U3 informative measurement (no gate): wallets>={}, dayJobMs={}, dailyLossJobMs={}, targetMs=5000",
                WALLETS,
                dayTook.toMillis(),
                lossTook.toMillis());
        assertThat(day.ran()).isTrue();
        assertThat(day.photos()).isGreaterThanOrEqualTo(WALLETS);
        assertThat(loss.ran()).isTrue();
    }

    private record Measured(boolean buy, Duration took, GateDecision decision) {}

    private Measured measure(OrderIntent intent) {
        long started = System.nanoTime();
        GateDecision decision = transactions.execute(status -> gate.evaluate(intent));
        return new Measured(intent.isBuy(), Duration.ofNanos(System.nanoTime() - started), decision);
    }

    private static Duration p95(List<Measured> measured, boolean buy) {
        List<Duration> times = measured.stream()
                .filter(m -> m.buy() == buy)
                .map(Measured::took)
                .sorted(Comparator.naturalOrder())
                .toList();
        return times.get(Math.max(0, (int) Math.ceil(times.size() * 0.95) - 1));
    }
}
