package com.memetrade.txsigner.infra.audit;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.memetrade.txsigner.domain.SignerEvent;
import com.memetrade.txsigner.domain.SignerEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audit outlet of the TxSigner (BR6.1, NFR1.7): one structured JSON line per decision on the logger {@code audit},
 * shipped with the process logs, and the counter {@code txsigner_decisions_total{kind,decision,reason}} without any
 * address, tenant or user label (observability-design.md). The bot wallet address is masked by the log encoder.
 */
public class LoggingSignerEvents implements SignerEvents {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private final MeterRegistry meters;
    private final SwapDecisionMetrics swaps;

    /** Registers the swap counters with zero at start-up (U4-NFR5.5). */
    public LoggingSignerEvents(MeterRegistry meters) {
        this.meters = meters;
        this.swaps = new SwapDecisionMetrics(meters);
    }

    @Override
    public void publish(SignerEvent event) {
        AUDIT.info(
                "txsigner event {}",
                kv("eventType", event.type().name()),
                kv("botWalletAddress", event.botWalletAddress()),
                kv("requestId", event.requestId()),
                kv("operation", event.operation()),
                kv("reason", event.reason()),
                kv("occurredAt", event.occurredAt()));
        decisionOf(event).ifPresent(decision -> Counter.builder("txsigner_decisions")
                .description("Decisions of the TxSigner by kind, decision and reason")
                .tag("kind", nullToNone(event.operation()))
                .tag("decision", decision)
                .tag("reason", nullToNone(event.reason()))
                .register(meters)
                .increment());
        if ("SWAP".equals(event.operation())) {
            switch (event.type()) {
                case SIGNED -> swaps.signed();
                case SIGNING_REFUSED, REQUEST_MISMATCH -> swaps.refused(event.reason());
                default -> {
                    // Other events of a swap are not decisions.
                }
            }
        }
    }

    private static java.util.Optional<String> decisionOf(SignerEvent event) {
        return switch (event.type()) {
            case SIGNED, WALLET_CREATED, POLICY_CHANGED -> java.util.Optional.of("SIGNED");
            case SIGNING_REFUSED, WALLET_CREATION_REFUSED, POLICY_CHANGE_REFUSED, REQUEST_MISMATCH ->
                java.util.Optional.of("REFUSED");
            default -> java.util.Optional.empty();
        };
    }

    private static String nullToNone(String value) {
        return value == null ? "NONE" : value;
    }
}
