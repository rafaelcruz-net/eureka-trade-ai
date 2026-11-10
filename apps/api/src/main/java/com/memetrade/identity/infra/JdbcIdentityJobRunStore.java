package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code identity_job_run} (migration 0012) through the main pool of Identity: one row per purge, upserted at the end
 * of each run inside the short transaction that also publishes the event of the run (reliability-design.md NFR7.4).
 * Only the Identity purges and their health indicators use this table (NFR6.4).
 */
@Repository
public class JdbcIdentityJobRunStore implements PurgeRunStore {

    private final JdbcClient jdbc;

    public JdbcIdentityJobRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PurgeRun> find(IdentityPurge purge) {
        return jdbc.sql("""
                        select purge_id, last_run_at, last_result, last_success_at, last_cutoff, deleted
                        from identity_job_run where purge_id = :purge
                        """)
                .param("purge", purge.purgeId())
                .query(JdbcIdentityJobRunStore::map)
                .optional();
    }

    @Override
    public void record(PurgeRun run) {
        jdbc.sql("""
                        insert into identity_job_run
                          (purge_id, last_run_at, last_result, last_success_at, last_cutoff, deleted)
                        values (:purge, :runAt, :result, :successAt, :cutoff, :deleted)
                        on conflict (purge_id) do update set
                            last_run_at = excluded.last_run_at,
                            last_result = excluded.last_result,
                            last_success_at = coalesce(excluded.last_success_at, identity_job_run.last_success_at),
                            last_cutoff = excluded.last_cutoff,
                            deleted = excluded.deleted
                        """)
                .param("purge", run.purge().purgeId())
                .param("runAt", Timestamp.from(run.lastRunAt()))
                .param("result", run.lastResult().name())
                .param("successAt", run.lastSuccessAt().map(Timestamp::from).orElse(null))
                .param("cutoff", Timestamp.from(run.lastCutoff()))
                .param("deleted", run.deleted())
                .update();
    }

    private static PurgeRun map(ResultSet rs, int row) throws SQLException {
        return new PurgeRun(
                IdentityPurge.fromPurgeId(rs.getString("purge_id")),
                rs.getTimestamp("last_run_at").toInstant(),
                PurgeResult.valueOf(rs.getString("last_result")),
                Optional.ofNullable(rs.getTimestamp("last_success_at")).map(Timestamp::toInstant),
                rs.getTimestamp("last_cutoff").toInstant(),
                rs.getLong("deleted"));
    }
}
