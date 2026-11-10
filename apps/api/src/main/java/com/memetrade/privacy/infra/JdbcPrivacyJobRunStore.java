package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.purge.PurgeResult;
import com.memetrade.privacy.domain.purge.PurgeRun;
import com.memetrade.privacy.domain.purge.PurgeRunStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code privacy_job_run} (migration 0014) through the main pool: one row per Privacy purge, upserted at the end of each
 * run inside the short transaction that also publishes the event of the run (reliability-design.md NFR7.1).
 */
@Repository
public class JdbcPrivacyJobRunStore implements PurgeRunStore {

    private final JdbcClient jdbc;

    public JdbcPrivacyJobRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PurgeRun> find(PrivacyPurge purge) {
        return jdbc.sql("""
                        select purge_id, last_run_at, last_result, last_success_at, last_cutoff, deleted
                        from privacy_job_run where purge_id = :purge
                        """)
                .param("purge", purge.purgeId())
                .query(JdbcPrivacyJobRunStore::map)
                .optional();
    }

    @Override
    public void record(PurgeRun run) {
        jdbc.sql("""
                        insert into privacy_job_run
                          (purge_id, last_run_at, last_result, last_success_at, last_cutoff, deleted)
                        values (:purge, :runAt, :result, :successAt, :cutoff, :deleted)
                        on conflict (purge_id) do update set
                            last_run_at = excluded.last_run_at,
                            last_result = excluded.last_result,
                            last_success_at = coalesce(excluded.last_success_at, privacy_job_run.last_success_at),
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
                PrivacyPurge.fromPurgeId(rs.getString("purge_id")),
                rs.getTimestamp("last_run_at").toInstant(),
                PurgeResult.valueOf(rs.getString("last_result")),
                Optional.ofNullable(rs.getTimestamp("last_success_at")).map(Timestamp::toInstant),
                rs.getTimestamp("last_cutoff").toInstant(),
                rs.getLong("deleted"));
    }
}
