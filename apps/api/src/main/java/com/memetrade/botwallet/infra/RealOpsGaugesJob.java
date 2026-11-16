package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.ReleaseState;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (U5-NFR5.1, U5-NFR5.3 to U5-NFR5.5, U5-NFR5.13): the job {@code ops-gauges} of the worker,
 * every 30 seconds on every instance (a read only; the alerts take the {@code max} across instances). It reads the
 * releases by state, the oldest BLOCKED release, the divergences OPEN or COMPENSATED, the wallets DEFERRED for more than
 * 2 hours, the kill switch and its unsent orders, the phase of the restore pause and the pending commands to the
 * TxSigner, and counts its heartbeat. Only counts and ages: no address, no amount.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RealOpsGaugesJob {

    static final String JOB = "ops-gauges";

    private final JdbcClient jdbc;
    private final GlobalPauses pauses;
    private final RealExecutionPort execution;
    private final RealOpsMetrics metrics;
    private final RealOpsProperties properties;
    private final Clock clock;

    public RealOpsGaugesJob(
            JdbcClient jdbc,
            GlobalPauses pauses,
            RealExecutionPort execution,
            RealOpsMetrics metrics,
            RealOpsProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.pauses = pauses;
        this.execution = execution;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${memetrade.real.ops.gauges-interval}")
    public void run() {
        CorrelationId.runWithNew(() -> {
            long started = System.nanoTime();
            metrics.gauges(read());
            metrics.jobRan(JOB, Duration.ofNanos(System.nanoTime() - started));
        });
    }

    /** One reading of every gauge of the operations of the REAL. */
    RealOpsMetrics.GaugeValues read() {
        Instant now = clock.instant();
        Map<ReleaseState, Long> releases = new EnumMap<>(ReleaseState.class);
        for (ReleaseState state : ReleaseState.values()) {
            releases.put(state, 0L);
        }
        jdbc.sql("select state, count(*) as n from real_release group by state")
                .query((rs, row) -> {
                    releases.put(ReleaseState.valueOf(rs.getString("state")), rs.getLong("n"));
                    return null;
                })
                .list();
        long blockedOldest = ageOfOldest("select min(effective_at) from real_release where state = 'BLOCKED'", now);
        long divergences = jdbc.sql("select count(*) from real_divergence where state in ('OPEN', 'COMPENSATED')")
                .query(Long.class)
                .single();
        long deferred = jdbc.sql("""
                        select count(*) from bot_wallet_account w
                        where w.status = 'ACTIVE' and exists (
                          select 1 from real_reconciliation_run r
                          where r.bot_wallet_id = w.bot_wallet_id
                            and r.result in ('DEFERRED', 'UNAVAILABLE')
                            and r.started_at <= :cutoff
                            and r.started_at > coalesce((select max(m.started_at) from real_reconciliation_run m
                              where m.bot_wallet_id = w.bot_wallet_id and m.result in ('CLEAN', 'DIVERGENT')),
                              '-infinity'::timestamptz))""")
                .param("cutoff", Timestamp.from(now.minus(properties.deferredAlertAfter())))
                .query(Long.class)
                .single();
        boolean killSwitch = pauses.killSwitch().active();
        long unsent = killSwitch ? execution.unsentOrders() : 0;
        String phase =
                pauses.openRestoreMarker().map(GlobalPauses.OpenMarker::phase).orElse(null);
        Map<String, Long> pending = new LinkedHashMap<>();
        jdbc.sql(
                        "select kind, count(*) as n from real_pending_txsigner_command where confirmed_at is null group by kind")
                .query((rs, row) -> {
                    pending.put(rs.getString("kind"), rs.getLong("n"));
                    return null;
                })
                .list();
        long pendingOldest = ageOfOldest(
                "select min(created_at) from real_pending_txsigner_command where confirmed_at is null", now);
        return new RealOpsMetrics.GaugeValues(
                releases, blockedOldest, divergences, deferred, killSwitch, unsent, phase, pending, pendingOldest);
    }

    /** The age in seconds of the instant one {@code min(...)} query returns; zero when there is none. */
    private long ageOfOldest(String sql, Instant now) {
        return jdbc.sql(sql).query(Timestamp.class).list().stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(oldest ->
                        Math.max(0, Duration.between(oldest.toInstant(), now).toSeconds()))
                .orElse(0L);
    }
}
