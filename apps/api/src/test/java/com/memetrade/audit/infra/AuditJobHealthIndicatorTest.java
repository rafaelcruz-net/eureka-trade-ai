package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobPolicy;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.audit.domain.AuditJobRun;
import com.memetrade.audit.domain.AuditJobRunStore;
import com.memetrade.shared.health.HealthStatuses;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;

/** NFR9.1: the job health component never fails hard; a database error only degrades it. */
@Tag("u2")
class AuditJobHealthIndicatorTest {

    private final MutableClock clock = new MutableClock();
    private final AuditJobPolicy policy = new AuditJobPolicy(Duration.ofHours(24), Duration.ofHours(26));

    @Test
    void aRecentSuccessIsUpWithItsDetails() {
        Instant success = clock.instant().minus(Duration.ofHours(3));
        AuditJobRun run = new AuditJobRun(
                AuditJob.AUDIT_RETENTION_PURGE,
                success.minusSeconds(4),
                AuditJobResult.COMPLETED,
                Optional.of(success),
                Optional.empty());

        Health health = indicator(job -> Optional.of(run)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("job", "AUDIT_RETENTION_PURGE")
                .containsEntry("lastResult", "COMPLETED")
                .containsEntry("lastSuccessAt", success.toString())
                .containsEntry("hoursSinceLastSuccess", 3L);
    }

    @Test
    void noRowIsDegradedAsNeverRun() {
        Health health = indicator(job -> Optional.empty()).health();

        assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(health.getDetails()).containsEntry("lastResult", "NEVER_RUN");
    }

    @Test
    void aDatabaseErrorIsDegradedWithTheErrorClassOnly() {
        Health health = indicator(job -> {
                    throw new DataAccessResourceFailureException("connection refused to db:5432");
                })
                .health();

        assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(health.getDetails()).containsEntry("error", "DataAccessResourceFailureException");
        assertThat(health.getDetails().toString()).doesNotContain("5432");
    }

    private AuditJobHealthIndicator indicator(Finder finder) {
        AuditJobRunStore store = new AuditJobRunStore() {
            @Override
            public Optional<AuditJobRun> find(AuditJob job) {
                return finder.find(job);
            }

            @Override
            public void record(
                    AuditJob job, Instant startedAt, AuditJobResult result, Instant endedAt, Instant cutoff) {
                throw new UnsupportedOperationException();
            }
        };
        return new AuditJobHealthIndicator(AuditJob.AUDIT_RETENTION_PURGE, store, policy, clock);
    }

    @FunctionalInterface
    private interface Finder {
        Optional<AuditJobRun> find(AuditJob job);
    }
}
