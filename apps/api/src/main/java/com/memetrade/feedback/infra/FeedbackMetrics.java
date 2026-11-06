package com.memetrade.feedback.infra;

import com.memetrade.feedback.domain.FeedbackEvents;
import com.memetrade.feedback.domain.FeedbackScreen;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metrics of the feedback (observability-design.md, NFR9.2): {@code feedback.received_total{screen}} and
 * {@code feedback.refused_total{reason}}. Labels are closed values; never a tenant, a person or the text.
 */
@Component
public class FeedbackMetrics implements FeedbackEvents {

    private final MeterRegistry registry;

    public FeedbackMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void received(FeedbackScreen screen) {
        Counter.builder("feedback.received_total")
                .tag("screen", screen.name())
                .register(registry)
                .increment();
    }

    @Override
    public void refused(Refusal reason) {
        Counter.builder("feedback.refused_total")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }
}
