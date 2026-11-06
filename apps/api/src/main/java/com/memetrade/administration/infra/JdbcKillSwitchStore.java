package com.memetrade.administration.infra;

import com.memetrade.administration.domain.KillSwitchChange;
import com.memetrade.administration.domain.KillSwitchSnapshot;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.administration.domain.SwitchState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link KillSwitchStore} over {@code kill_switch_state} (one row, id 1) and {@code kill_switch_change}. The mark of
 * reconciliation and the start of an application use the DATABASE clock ({@code clock_timestamp()}): it is a fencing
 * token between sessions, never a business instant, so the injectable clock of the domain is not involved and two
 * events in different sessions are always ordered (reliability-design.md R-09).
 */
@Repository
public class JdbcKillSwitchStore implements KillSwitchStore {

    private static final String COLUMNS =
            "state, changed_by, changed_at, reason, pending_bots, reconcile_needed, version";

    private final JdbcClient jdbc;

    public JdbcKillSwitchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public KillSwitchSnapshot read() {
        return jdbc.sql("select " + COLUMNS + " from kill_switch_state where id = 1")
                .query(JdbcKillSwitchStore::snapshot)
                .single();
    }

    @Override
    public KillSwitchSnapshot lockForUpdate() {
        return jdbc.sql("select " + COLUMNS + " from kill_switch_state where id = 1 for update")
                .query(JdbcKillSwitchStore::snapshot)
                .single();
    }

    @Override
    public void writeState(StateWrite write) {
        int rows = jdbc.sql("""
                        update kill_switch_state
                        set state = :state, changed_by = :by, changed_at = :at, reason = :reason, version = :version,
                            pending_bots = case when :state = 'OFF' then 0 else pending_bots end
                        where id = 1 and version = :version - 1
                        """)
                .param("state", write.state().name())
                .param("by", write.changedBy())
                .param("at", Timestamp.from(write.changedAt()))
                .param("reason", write.reason())
                .param("version", write.version())
                .update();
        if (rows != 1) {
            throw new IllegalStateException("The kill switch row changed although it was locked");
        }
    }

    @Override
    public void appendChange(KillSwitchChange change) {
        jdbc.sql("""
                        insert into kill_switch_change (change_id, version, from_state, to_state, admin_user_id, reason,
                            changed_at, correlation_id)
                        values (:id, :version, :from, :to, :admin, :reason, :at, :correlation)
                        """)
                .param("id", change.changeId())
                .param("version", change.version())
                .param("from", change.from().name())
                .param("to", change.to().name())
                .param("admin", change.adminUserId())
                .param("reason", change.reason())
                .param("at", Timestamp.from(change.changedAt()))
                .param("correlation", change.correlationId())
                .update();
    }

    @Override
    public void markReconcile() {
        jdbc.sql("""
                        update kill_switch_state set reconcile_needed = true, reconcile_marked_at = clock_timestamp()
                        where id = 1
                        """).update();
    }

    @Override
    public Fence fence() {
        return jdbc.sql("select state, version, clock_timestamp() as started_at from kill_switch_state where id = 1")
                .query((rs, n) -> new Fence(
                        SwitchState.valueOf(rs.getString("state")),
                        rs.getLong("version"),
                        rs.getTimestamp("started_at").toInstant()))
                .single();
    }

    @Override
    public boolean recordOutcome(Fence fence, int pendingBots) {
        return jdbc.sql("""
                                update kill_switch_state
                                set pending_bots = :pending, reconcile_needed = false
                                where id = 1 and version = :version
                                  and (reconcile_marked_at is null or reconcile_marked_at < :started)
                                """)
                        .param("pending", pendingBots)
                        .param("version", fence.version())
                        .param("started", Timestamp.from(fence.startedAt()))
                        .update()
                == 1;
    }

    private static KillSwitchSnapshot snapshot(ResultSet rs, int row) throws SQLException {
        Timestamp changedAt = rs.getTimestamp("changed_at");
        return new KillSwitchSnapshot(
                SwitchState.valueOf(rs.getString("state")),
                Optional.ofNullable(rs.getObject("changed_by", UUID.class)),
                Optional.ofNullable(changedAt).map(Timestamp::toInstant),
                Optional.ofNullable(rs.getString("reason")),
                rs.getInt("pending_bots"),
                rs.getBoolean("reconcile_needed"),
                rs.getLong("version"));
    }
}
