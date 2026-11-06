package com.memetrade.feedback.infra;

import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component {@code feedbackPurge} (observability-design.md, alert {@code FEEDBACK_PURGE_STALE}): {@code UP} while
 * the last successful purge, or the start of the process before the first one, is at most 48 hours old; {@code DOWN}
 * with the code otherwise. Outside liveness and readiness. Details carry instants and a code only.
 */
@Component("feedbackPurgeHealthIndicator")
@ProcessRole(ProcessRole.Kind.API)
public class FeedbackPurgeHealthIndicator implements HealthIndicator {

    static final String STALE = "FEEDBACK_PURGE_STALE";

    private final FeedbackPurgeJob job;
    private final FeedbackProperties settings;
    private final Clock clock;

    public FeedbackPurgeHealthIndicator(FeedbackPurgeJob job, FeedbackProperties settings, Clock clock) {
        this.job = job;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public Health health() {
        return evaluate(job.lastSuccessAt(), job.startedAt(), clock.instant(), settings.purgeStaleAfter());
    }

    static Health evaluate(Optional<Instant> lastSuccess, Instant processStart, Instant now, Duration staleAfter) {
        Instant reference = lastSuccess.orElse(processStart);
        boolean fresh = Duration.between(reference, now).compareTo(staleAfter) <= 0;
        Health.Builder builder = fresh ? Health.up() : Health.down().withDetail("code", STALE);
        lastSuccess.ifPresent(at -> builder.withDetail("lastSuccessAt", at.toString()));
        return builder.build();
    }
}
