package com.memetrade.riskcontrol.infra;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Health component {@code riskcontrol} (NFR9.1, reliability-design.md), read from {@code risk_purge_run} (the API and
 * the worker read it the same way) and OUTSIDE the liveness and readiness groups, so a failed cleanup never takes the
 * API down nor changes the healthcheck of Docker Compose.
 *
 * <ul>
 *   <li>{@code UP}: the last succeeded round started less than 26 hours ago, or there was no round yet and the process
 *       started less than 26 hours ago.
 *   <li>{@code DOWN}: otherwise, with {@code reason} {@code PURGE_FAILED} (the last round failed) or
 *       {@code PURGE_STALE} (no recent success).
 * </ul>
 *
 * The details carry instants and closed values only, never a person.
 */
@Component("riskcontrolHealthIndicator")
public class RiskControlHealthIndicator implements HealthIndicator {

    static final Duration STALE_AFTER = Duration.ofHours(26);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Instant startedAt;

    public RiskControlHealthIndicator(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /** The last round (any result) and the last success, as recorded by the purge. */
    record Runs(Optional<LastRun> last, Optional<Instant> lastSuccessAt) {}

    record LastRun(Instant startedAt, String result) {}

    @Override
    public Health health() {
        return evaluate(read(), clock.instant(), startedAt);
    }

    /** The rule of the indicator, separate from the reading (unit tested). */
    static Health evaluate(Runs runs, Instant now, Instant processStartedAt) {
        boolean fresh = runs.lastSuccessAt()
                .map(at -> Duration.between(at, now).compareTo(STALE_AFTER) < 0)
                .orElse(runs.last().isEmpty()
                        && Duration.between(processStartedAt, now).compareTo(STALE_AFTER) < 0);
        Health.Builder builder = fresh ? Health.up() : Health.down();
        runs.last().ifPresent(last -> builder.withDetail(
                        "lastRunAt", last.startedAt().toString())
                .withDetail("lastResult", last.result()));
        runs.lastSuccessAt().ifPresent(at -> builder.withDetail("lastSuccessAt", at.toString()));
        if (!fresh) {
            boolean failed =
                    runs.last().map(last -> "FAILED".equals(last.result())).orElse(false);
            builder.withDetail("reason", failed ? "PURGE_FAILED" : "PURGE_STALE");
        }
        return builder.build();
    }

    private Runs read() {
        Optional<LastRun> last = jdbc.sql(
                        "select started_at, result from risk_purge_run order by started_at desc limit 1")
                .query((rs, n) -> new LastRun(rs.getTimestamp("started_at").toInstant(), rs.getString("result")))
                .optional();
        Optional<Instant> success = jdbc.sql("select max(started_at) from risk_purge_run where result = 'SUCCEEDED'")
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
        return new Runs(last, success);
    }
}
