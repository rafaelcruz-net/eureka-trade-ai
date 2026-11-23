package com.memetrade.pnl.application;

import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U2 (NFR1.23, NFR2.11, NFR3.16, observability-design.md): the metrics of the P&L REAL, every
 * series registered with 0 when the process starts, so the first one shows in {@code increase()}:
 *
 * <ul>
 *   <li>the timer {@code pnl.real.attribution.read} ({@code pnl_real_attribution_read_seconds}) around the read with
 *       attribution of each path, with fixed buckets of 0.1, 0.25, 0.5, 0.8, 1 and 2 s, all 12 combinations of
 *       {@code path} and {@code outcome};
 *   <li>in the api only: {@code pnl.real.reconciliation.mismatches} ({@code pnl_real_reconciliation_mismatches_total},
 *       one series per {@code check}, +1 per failed check of every read with a mismatch) and
 *       {@code pnl.real.reconciliation.audit_failures} ({@code pnl_real_reconciliation_audit_failures_total}, +1 per
 *       write of the event that failed, passed its budget or had no time left).
 * </ul>
 *
 * No label carries a tenant, a person, a bot, a wallet, an address, a signature, a mint nor a correlation id.
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

    public static final String MISMATCHES = "pnl.real.reconciliation.mismatches";
    public static final String AUDIT_FAILURES = "pnl.real.reconciliation.audit_failures";

    private final Map<CostPath, Map<Outcome, Timer>> reads = new EnumMap<>(CostPath.class);
    private final Map<ReconciliationResult.Check, Counter> mismatches = new EnumMap<>(ReconciliationResult.Check.class);
    private final Counter auditFailures;

    /** Every series, as in the api (the tests that build the readers without Spring). */
    public PnlRealMetrics(MeterRegistry registry) {
        this(registry, ProcessRole.Kind.API);
    }

    @Autowired
    public PnlRealMetrics(MeterRegistry registry, Environment environment) {
        this(registry, ProcessRole.Kind.parse(environment.getProperty(ProcessRole.PROPERTY)));
    }

    private PnlRealMetrics(MeterRegistry registry, ProcessRole.Kind process) {
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
        if (process == ProcessRole.Kind.API) {
            for (ReconciliationResult.Check check : ReconciliationResult.Check.values()) {
                mismatches.put(
                        check,
                        Counter.builder(MISMATCHES)
                                .description("Checks of the P&L REAL that did not reconcile with the ledger (NFR2.11)")
                                .tag("check", check.name())
                                .register(registry));
            }
            auditFailures = Counter.builder(AUDIT_FAILURES)
                    .description("Writes of the event of a REAL P&L mismatch that failed or ran out of time")
                    .register(registry);
        } else {
            auditFailures = null;
        }
    }

    public void attributionRead(CostPath path, Outcome outcome, Duration took) {
        reads.get(path).get(outcome).record(took);
    }

    /** One read with a mismatch: +1 per failed check. */
    public void mismatch(Collection<ReconciliationResult.Check> failed) {
        failed.forEach(check -> mismatches.get(check).increment());
    }

    /** One write of the event that failed. */
    public void auditFailure() {
        auditFailures.increment();
    }
}
