package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * BR9.1 (Q14, U4-NFR4.6): the consecutive failures of one provider for one person in {@code real_upstream_health}, in
 * the transaction of the caller. The third failure in a row pauses the REAL of the person (UPSTREAM, through M1) and is
 * counted; a success of the same provider for the same person resets the count. The withdrawal never passes here.
 */
@Component
public class UpstreamTracker {

    private final JdbcClient jdbc;
    private final RealPauses pauses;
    private final RealExecutionMetrics metrics;
    private final RealExecutionProperties properties;
    private final Clock clock;

    public UpstreamTracker(
            JdbcClient jdbc,
            RealPauses pauses,
            RealExecutionMetrics metrics,
            RealExecutionConfig.CheckedSettings settings,
            Clock clock) {
        this.jdbc = jdbc;
        this.pauses = pauses;
        this.metrics = metrics;
        this.properties = settings.properties();
        this.clock = clock;
    }

    /** A technical failure of {@code provider} for the person, in the transaction of the caller. */
    public void failed(UUID tenantId, UUID userId, RealExecutionMetrics.Provider provider) {
        Instant now = clock.instant();
        int failures = jdbc.sql("""
                        insert into real_upstream_health (subject_id, provider, tenant_id, consecutive_failures,
                          updated_at)
                        values (:user, :provider, :tenant, 1, :now)
                        on conflict (subject_id, provider)
                        do update set consecutive_failures = real_upstream_health.consecutive_failures + 1,
                          updated_at = excluded.updated_at
                        returning consecutive_failures""")
                .param("user", userId)
                .param("provider", provider.name())
                .param("tenant", tenantId)
                .param("now", Timestamp.from(now))
                .query(Integer.class)
                .single();
        if (failures >= properties.upstreamFailuresToPause()
                && pauses.pauseUser(tenantId, userId, RealPauseReason.UPSTREAM) == RealPauses.PauseOutcome.PAUSED) {
            metrics.upstreamPause();
        }
    }

    /** A success of {@code provider} for the person resets the count. */
    public void succeeded(UUID tenantId, UUID userId, RealExecutionMetrics.Provider provider) {
        jdbc.sql("""
                        update real_upstream_health set consecutive_failures = 0, updated_at = :now
                        where subject_id = :user and provider = :provider and consecutive_failures > 0""")
                .param("user", userId)
                .param("provider", provider.name())
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }
}
