package com.memetrade.txsigner.infra.audit;

import com.memetrade.txsigner.policy.PolicyRefusal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * U4-NFR5.5 of 261113-trading-real-bot: {@code txsigner_swap_decisions_total{outcome,reason}}, with closed labels only
 * (the reasons of the swap policy, never an address). Every combination the alerts use is registered with zero at
 * start-up, so {@code increase(...{outcome="REFUSED"}[5m]) > 0} fires on the first refusal.
 */
public class SwapDecisionMetrics {

    static final String NAME = "txsigner_swap_decisions";

    private final MeterRegistry meters;

    public SwapDecisionMetrics(MeterRegistry meters) {
        this.meters = meters;
        counter("SIGNED", "NONE");
        PolicyRefusal.SWAP_REASONS.forEach(reason -> counter("REFUSED", reason.name()));
    }

    public void signed() {
        counter("SIGNED", "NONE").increment();
    }

    public void refused(String reason) {
        String label = reason;
        try {
            label = PolicyRefusal.valueOf(reason).name();
        } catch (IllegalArgumentException | NullPointerException unknown) {
            label = "OTHER";
        }
        counter("REFUSED", label).increment();
    }

    private Counter counter(String outcome, String reason) {
        return Counter.builder(NAME)
                .description("Swap decisions of the TxSigner by outcome and reason of refusal")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(meters);
    }
}
