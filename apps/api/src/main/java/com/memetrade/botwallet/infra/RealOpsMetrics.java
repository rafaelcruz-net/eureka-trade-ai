package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.BlockedReason;
import com.memetrade.botwallet.domain.ReleaseState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (observability-requirements.md U5-NFR5.1 to U5-NFR5.6, observability-design.md): the
 * metrics of the REAL operations. Every label comes from a closed enum, never a person, an address or a mint; every
 * counter is registered with zero at start-up for every value of its label. The gauges read from the database are set
 * only in the worker, by the {@code ops-gauges} job every 30 s, and exist only once it ran (the stopped-job alert of
 * {@code ops-gauges} covers their absence).
 */
@Component
public class RealOpsMetrics {

    /** The closed label {@code job} (U5-NFR5.6, U5-NFR5.13). */
    public static final List<String> JOBS = List.of(
            "gates", "reconciliation", "kill-switch-cancel", "txsigner-resend", "restore-check", "ops-gauges", "purge");

    /** The closed label {@code result} of the reconciliation. */
    public static final List<String> RESULTS = List.of("CLEAN", "DIVERGENT", "DEFERRED", "UNAVAILABLE");

    /** The closed label {@code kind} of the commands to the TxSigner. */
    public static final List<String> KINDS = List.of("LOWER_CAP", "DEACTIVATE");

    /** The closed label {@code phase} of the restore pause. */
    public static final List<String> PHASES = List.of("CHECK_PENDING", "RESTORE");

    private final MeterRegistry meters;
    private final AtomicBoolean gaugesRegistered = new AtomicBoolean();
    private final Map<ReleaseState, AtomicLong> releases = new EnumMap<>(ReleaseState.class);
    private final AtomicLong blockedOldest = new AtomicLong();
    private final AtomicLong divergencesOpen = new AtomicLong();
    private final AtomicLong deferredOver2h = new AtomicLong();
    private final AtomicLong killSwitchActive = new AtomicLong();
    private final AtomicLong killSwitchUnsent = new AtomicLong();
    private final Map<String, AtomicLong> restorePause = new LinkedHashMap<>();
    private final Map<String, AtomicLong> pendingCommands = new LinkedHashMap<>();
    private final AtomicLong pendingOldest = new AtomicLong();

    public RealOpsMetrics(MeterRegistry meters) {
        this.meters = meters;
        for (BlockedReason reason : BlockedReason.values()) {
            blocks(reason);
        }
        RESULTS.forEach(this::reconciliations);
        for (String job : JOBS) {
            runs(job);
            jobTimer(job);
        }
        reconciliationTimer();
        for (ReleaseState state : ReleaseState.values()) {
            releases.put(state, new AtomicLong());
        }
        PHASES.forEach(phase -> restorePause.put(phase, new AtomicLong()));
        KINDS.forEach(kind -> pendingCommands.put(kind, new AtomicLong()));
    }

    /** U5-NFR5.1: a release moved to BLOCKED (or blocked for another reason). */
    public void blocked(BlockedReason reason) {
        blocks(reason).increment();
    }

    /** U5-NFR5.2: one reconciliation of one wallet with its result and its duration. */
    public void reconciled(String result, Duration took) {
        reconciliations(result).increment();
        reconciliationTimer().record(took);
    }

    /** U5-NFR5.6: the heartbeat of a job, counted at the end of every run that ended well, even with nothing to do. */
    public void jobRan(String job, Duration took) {
        runs(job).increment();
        jobTimer(job).record(took);
    }

    /** U5-NFR5.1, U5-NFR5.3 to U5-NFR5.5: the gauges read from the database by the {@code ops-gauges} job. */
    public void gauges(GaugeValues values) {
        registerGauges();
        values.releases().forEach((state, count) -> releases.get(state).set(count));
        blockedOldest.set(values.blockedOldestSeconds());
        divergencesOpen.set(values.divergencesOpen());
        deferredOver2h.set(values.deferredOver2h());
        killSwitchActive.set(values.killSwitchActive() ? 1 : 0);
        killSwitchUnsent.set(values.killSwitchUnsentOrders());
        PHASES.forEach(phase -> restorePause.get(phase).set(phase.equals(values.restorePhase()) ? 1 : 0));
        KINDS.forEach(
                kind -> pendingCommands.get(kind).set(values.pendingCommands().getOrDefault(kind, 0L)));
        pendingOldest.set(values.pendingOldestSeconds());
    }

    /** The values of the gauges of one run of {@code ops-gauges}. */
    public record GaugeValues(
            Map<ReleaseState, Long> releases,
            long blockedOldestSeconds,
            long divergencesOpen,
            long deferredOver2h,
            boolean killSwitchActive,
            long killSwitchUnsentOrders,
            String restorePhase,
            Map<String, Long> pendingCommands,
            long pendingOldestSeconds) {}

    private void registerGauges() {
        if (!gaugesRegistered.compareAndSet(false, true)) {
            return;
        }
        releases.forEach((state, value) -> Gauge.builder("real_releases", value, AtomicLong::get)
                .tag("state", state.name())
                .description("REAL releases by state")
                .register(meters));
        gauge("real_release_blocked_oldest_seconds", blockedOldest, "Age of the oldest BLOCKED release");
        gauge("real_divergences_open", divergencesOpen, "Divergences OPEN or COMPENSATED");
        gauge("real_reconciliation_deferred_over_2h", deferredOver2h, "Wallets DEFERRED for more than 2 hours");
        gauge("real_kill_switch_active", killSwitchActive, "The kill switch REAL is on (1) or off (0)");
        gauge("real_kill_switch_unsent_orders", killSwitchUnsent, "Unsent REAL orders while the kill switch is on");
        restorePause.forEach((phase, value) -> Gauge.builder("real_restore_pause_active", value, AtomicLong::get)
                .tag("phase", phase)
                .description("The restore pause of the REAL is active in this phase")
                .register(meters));
        pendingCommands.forEach((kind, value) -> Gauge.builder("real_pending_txsigner_commands", value, AtomicLong::get)
                .tag("kind", kind)
                .description("Commands to the TxSigner not yet confirmed")
                .register(meters));
        gauge("real_pending_txsigner_oldest_seconds", pendingOldest, "Age of the oldest unconfirmed command");
    }

    private void gauge(String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::get).description(description).register(meters);
    }

    private Counter blocks(BlockedReason reason) {
        return Counter.builder("real_release_blocks")
                .tag("reason", reason.name())
                .description("Releases blocked by a red gate")
                .register(meters);
    }

    private Counter reconciliations(String result) {
        return Counter.builder("real_reconciliations")
                .tag("result", result)
                .description("Reconciliations of a bot wallet by result")
                .register(meters);
    }

    private Counter runs(String job) {
        return Counter.builder("real_ops_job_runs")
                .tag("job", job)
                .description("Runs of a job of the REAL operations that ended well")
                .register(meters);
    }

    private Timer jobTimer(String job) {
        return Timer.builder("real_ops_job")
                .tag("job", job)
                .description("Duration of a run of a job of the REAL operations")
                .register(meters);
    }

    private Timer reconciliationTimer() {
        return Timer.builder("real_reconciliation")
                .description("Duration of the reconciliation of one bot wallet")
                .serviceLevelObjectives(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30))
                .register(meters);
    }
}
