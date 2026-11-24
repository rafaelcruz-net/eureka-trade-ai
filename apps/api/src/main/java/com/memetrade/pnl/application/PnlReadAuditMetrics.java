package com.memetrade.pnl.application;

import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U3 (NFR3.16; observability-design.md): {@code pnl.read_audit.failures{action}}, the audit
 * events of the refusals of the reads of the P&L that could not be written (full queue, deadline, connection or any
 * other failure). 2 series, registered with 0 at start-up; the labels carry only the closed action.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class PnlReadAuditMetrics {

    public static final String FAILURES = "pnl.read_audit.failures";

    private final Map<ReadAuditAction, Counter> failures = new EnumMap<>(ReadAuditAction.class);

    public PnlReadAuditMetrics(MeterRegistry meters) {
        for (ReadAuditAction action : ReadAuditAction.values()) {
            failures.put(
                    action,
                    Counter.builder(FAILURES)
                            .description("Audit events of refused reads of the P&L that could not be written (NFR3.16)")
                            .tag("action", action.name())
                            .register(meters));
        }
    }

    /** One event of {@code action} was not written. */
    public void failure(ReadAuditAction action) {
        failures.get(action).increment();
    }
}
