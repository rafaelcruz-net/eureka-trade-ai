package com.memetrade.bots.loadtest;

import com.memetrade.bots.worker.CycleResult;
import com.memetrade.bots.worker.CycleTimings;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE, in the worker, when {@code memetrade.loadtest.bots.enabled} is true (load
 * scenario {@code u5}): the stopwatch of the cycle stages (performance-requirements.md, NFR2.1 and NFR2.3). Each cycle
 * feeds three timers with exact buckets at the limits ({@code loadtest.bots.cycle}, {@code loadtest.bots.decision},
 * {@code loadtest.bots.gate}, read on the worker actuator at 127.0.0.1:8082) and writes one log line
 * {@code "Loadtest cycle timing totalMs=.. decisionMs=.. gateMs=.. kind=.."} that the load script also reads. There is
 * no such histogram in production.
 */
@Component
@Profile("loadtest")
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.loadtest.bots.enabled", havingValue = "true")
public class CycleStopwatch implements CycleTimings {

    private static final Logger log = LoggerFactory.getLogger(CycleStopwatch.class);
    private static final ThreadLocal<long[]> STAGES = ThreadLocal.withInitial(() -> new long[2]);

    private final Timer cycle;
    private final Timer decision;
    private final Timer gate;

    public CycleStopwatch(MeterRegistry registry) {
        this.cycle = timer(
                registry,
                "loadtest.bots.cycle",
                "Whole bot cycle, start of the transaction to commit",
                Duration.ofSeconds(2));
        this.decision = timer(
                registry,
                "loadtest.bots.decision",
                "Reads, decision and its record in one cycle",
                Duration.ofSeconds(1));
        this.gate = timer(
                registry,
                "loadtest.bots.gate",
                "OrderGate.evaluate plus PaperExecution.execute (NFR2.3)",
                Duration.ofMillis(500));
    }

    private static Timer timer(MeterRegistry registry, String name, String description, Duration limit) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(250), Duration.ofMillis(500), limit)
                .register(registry);
    }

    @Override
    public void decision(Duration duration) {
        decision.record(duration);
        STAGES.get()[0] += duration.toNanos();
    }

    @Override
    public void barrierAndExecution(Duration duration) {
        gate.record(duration);
        STAGES.get()[1] += duration.toNanos();
    }

    @Override
    public void cycle(Duration duration, CycleResult result) {
        cycle.record(duration);
        long[] stages = STAGES.get();
        log.info(
                "Loadtest cycle timing totalMs={} decisionMs={} gateMs={} kind={}",
                duration.toMillis(),
                stages[0] / 1_000_000,
                stages[1] / 1_000_000,
                result.kind());
        STAGES.remove();
    }
}
