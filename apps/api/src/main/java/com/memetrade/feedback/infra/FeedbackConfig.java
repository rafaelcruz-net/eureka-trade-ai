package com.memetrade.feedback.infra;

import com.memetrade.feedback.domain.FeedbackEvents;
import com.memetrade.feedback.domain.FeedbackService;
import com.memetrade.feedback.domain.FeedbackStore;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the submission of feedback in the API process only (the worker runs nothing of U8). */
@Configuration(proxyBeanMethods = false)
@ProcessRole(ProcessRole.Kind.API)
public class FeedbackConfig {

    @Bean
    public FeedbackService feedbackService(
            FeedbackStore store,
            IdempotencyGuard idempotency,
            FeedbackEvents events,
            Clock clock,
            FeedbackProperties properties) {
        return new FeedbackService(store, idempotency, events, clock, properties.maxPerHour());
    }
}
