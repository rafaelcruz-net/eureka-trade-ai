package com.memetrade.administration.infra;

import com.memetrade.administration.domain.KillSwitchSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Metrics of the kill switch (observability-design.md, NFR9.1). Labels are closed values only ({@code outcome}); never
 * a tenant, a person, a wallet or the reason. The gauges show the last state seen by the service, the applier or the
 * reconciliation, so a scrape never touches the database.
 */
@Component
public class KillSwitchMetrics {

    /** {@code outcome} label of the apply and lift timers. */
    public enum Outcome {
        OK,
        PARTIAL,
        FAILED
    }

    private final MeterRegistry registry;
    private final AtomicInteger state = new AtomicInteger();
    private final AtomicInteger pendingBots = new AtomicInteger();
    private final AtomicInteger reconcileNeeded = new AtomicInteger();
    private final Counter divergences;

    public KillSwitchMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("admin.killswitch.state", state, AtomicInteger::get)
                .description("1 when the kill switch is ON")
                .register(registry);
        Gauge.builder("admin.killswitch.pending_bots", pendingBots, AtomicInteger::get)
                .description("Bots whose system pause (or its lifting) still failed")
                .register(registry);
        Gauge.builder("admin.killswitch.reconcile_needed", reconcileNeeded, AtomicInteger::get)
                .description("1 while the last application of the kill switch is uncertain")
                .register(registry);
        this.divergences = Counter.builder("admin.killswitch.reconcile_divergences_total")
                .description("Reconciliation rounds that found U5 and U8 disagreeing")
                .register(registry);
    }

    public void observed(KillSwitchSnapshot snapshot) {
        state.set(snapshot.state().paused() ? 1 : 0);
        pendingBots.set(snapshot.pendingBots());
        reconcileNeeded.set(snapshot.reconcileNeeded() ? 1 : 0);
    }

    public void divergence() {
        divergences.increment();
    }

    /** One call of U5: {@code apply} for ON, {@code lift} for OFF. */
    public void call(boolean apply, Outcome outcome, Duration took) {
        Timer.builder(apply ? "admin.killswitch.apply_seconds" : "admin.killswitch.lift_seconds")
                .tag("outcome", outcome.name())
                .register(registry)
                .record(took);
    }

    public double divergenceCount() {
        return divergences.count();
    }
}
