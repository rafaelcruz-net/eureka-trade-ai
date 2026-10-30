package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.audit.domain.AuditJobRun;
import com.memetrade.audit.domain.AuditJobRunStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@code audit_job_run} through the main pool (application login); one row per job, upserted at the end of a run. */
@Repository
public class JdbcAuditJobRunStore implements AuditJobRunStore {

    private final JdbcClient jdbc;

    public JdbcAuditJobRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AuditJobRun> find(AuditJob job) {
        return jdbc.sql("""
                        select job_name, last_run_at, last_result, last_success_at, last_cutoff
                        from audit_job_run where job_name = :job
                        """)
                .param("job", job.name())
                .query(JdbcAuditJobRunStore::map)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditJob job, Instant startedAt, AuditJobResult result, Instant endedAt, Instant cutoff) {
        jdbc.sql("""
                        insert into audit_job_run (job_name, last_run_at, last_result, last_success_at, last_cutoff)
                        values (:job, :runAt, :result, :successAt, :cutoff)
                        on conflict (job_name) do update set
                            last_run_at = excluded.last_run_at,
                            last_result = excluded.last_result,
                            last_success_at = coalesce(excluded.last_success_at, audit_job_run.last_success_at),
                            last_cutoff = excluded.last_cutoff
                        """)
                .param("job", job.name())
                .param("runAt", Timestamp.from(startedAt))
                .param("result", result.name())
                .param("successAt", result == AuditJobResult.COMPLETED ? Timestamp.from(endedAt) : null)
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    private static AuditJobRun map(ResultSet rs, int row) throws SQLException {
        return new AuditJobRun(
                AuditJob.valueOf(rs.getString("job_name")),
                rs.getTimestamp("last_run_at").toInstant(),
                AuditJobResult.valueOf(rs.getString("last_result")),
                Optional.ofNullable(rs.getTimestamp("last_success_at")).map(Timestamp::toInstant),
                Optional.ofNullable(rs.getTimestamp("last_cutoff")).map(Timestamp::toInstant));
    }
}
