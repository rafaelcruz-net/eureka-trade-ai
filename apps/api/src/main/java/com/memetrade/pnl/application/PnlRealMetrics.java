package com.memetrade.pnl.application;

import com.memetrade.pnl.api.CostPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U2 (NFR1.23, observability-design.md): the timer {@code pnl.real.attribution.read} (exported
 * {@code pnl_real_attribution_read_seconds}) around the read with attribution of each path, with fixed buckets of 0.1,
 * 0.25, 0.5, 0.8, 1 and 2 s. Every combination of {@code path} and {@code outcome} is registered with 0 when the
 * process starts, so the first one shows in {@code increase()}. No label carries a tenant, a person, a bot, a wallet, an
 * address, a signature, a mint nor a correlation id.
 */
@Component
public class PnlRealMetrics {

    /** The {@code outcome} label: a read past its budget is TIMEOUT, never OK; INCONSISTENT_READ is an ERROR. */
    public enum Outcome {
        OK,
        TIMEOUT,
        ERROR,
        UNKNOWN_BOT
    }

    private final Map<CostPath, Map<Outcome, Timer>> reads = new EnumMap<>(CostPath.class);

    public PnlRealMetrics(MeterRegistry registry) {
        for (CostPath path : CostPath.values()) {
            Map<Outcome, Timer> outcomes = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(
                        outcome,
                        Timer.builder("pnl.real.attribution.read")
                                .description("The read of the REAL cost with attribution, per path and outcome")
                                .tag("path", path.name())
                                .tag("outcome", outcome.name())
                                .serviceLevelObjectives(
                                        Duration.ofMillis(100),
                                        Duration.ofMillis(250),
                                        Duration.ofMillis(500),
                                        Duration.ofMillis(800),
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(2))
                                .register(registry));
            }
            reads.put(path, outcomes);
        }
    }

    public void attributionRead(CostPath path, Outcome outcome, Duration took) {
        reads.get(path).get(outcome).record(took);
    }
}
