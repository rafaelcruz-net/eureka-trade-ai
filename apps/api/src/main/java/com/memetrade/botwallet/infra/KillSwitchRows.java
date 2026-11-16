package com.memetrade.botwallet.infra;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 261113-trading-real-bot U5 (BR3.1, BR3.2, U5-NFR4.1): the writes of the one row of {@code real_kill_switch}, each a
 * compare-and-set on the version read before, in the transaction of the caller. Two requests at the same time change
 * the row once: the second one finds the version moved and reads the new state.
 */
@Repository
public class KillSwitchRows {

    private final JdbcClient jdbc;

    public KillSwitchRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** On, only when it is off at {@code expectedVersion}. */
    public boolean activate(long expectedVersion, UUID adminId, String reason, Instant at) {
        return jdbc.sql("update real_kill_switch set active = true, activated_at = :at, activated_by = :admin, reason ="
                                + " :reason, version = version + 1, updated_at = :at, updated_by = :admin where switch_id"
                                + " = 1 and not active and version = :version")
                        .param("at", Timestamp.from(at))
                        .param("admin", adminId)
                        .param("reason", reason)
                        .param("version", expectedVersion)
                        .update()
                == 1;
    }

    /** Off, only when it is on at {@code expectedVersion}. */
    public boolean deactivate(long expectedVersion, UUID adminId, String reason, Instant at) {
        return jdbc.sql("update real_kill_switch set active = false, activated_at = null, activated_by = null, reason ="
                                + " :reason, version = version + 1, updated_at = :at, updated_by = :admin where switch_id"
                                + " = 1 and active and version = :version")
                        .param("at", Timestamp.from(at))
                        .param("admin", adminId)
                        .param("reason", reason)
                        .param("version", expectedVersion)
                        .update()
                == 1;
    }
}
