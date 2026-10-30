package com.memetrade.audit.infra;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only statistics of the trail used by the delivery measure (observability-design.md). */
@Component
public class JdbcTrailStatistics {

    private final JdbcClient jdbc;

    public JdbcTrailStatistics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Current value of the server parameter {@code track_commit_timestamp} ({@code on} or {@code off}). */
    public String trackCommitTimestamp() {
        return jdbc.sql("select setting from pg_settings where name = 'track_commit_timestamp'")
                .query(String.class)
                .single();
    }

    /** Events recorded by the relay in {@code [from, to)}; served by the {@code recorded_at} index. */
    public long countRecordedBetween(Instant from, Instant to) {
        return jdbc.sql("select count(*) from audit_event where recorded_at >= :from and recorded_at < :to")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Long.class)
                .single();
    }
}
