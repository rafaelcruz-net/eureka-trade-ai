package com.memetrade.bots.infra;

import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotStateMachine;
import com.memetrade.bots.domain.TenantScope;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * J2: the count of u4-data-requests (one count by tenant and owner through {@code bot_owner_state_idx}; read only) and
 * the anonymization of u5-anonymization (BR5.2, BR5.3, BR5.6; NFR3.1): the locks through {@link LockOrder} (the only
 * place of the module that writes a row lock), the rule {@code anonymizeStop} of the state machine, one update of the
 * bots to stop by primary key and one rename of every bot of the person. Nothing else of the bots changes: no closing,
 * no order, no limits; the live channel sees the change through the change mark.
 */
@Repository
public class JdbcBotOwnerDataPort implements BotOwnerDataPort {

    private final JdbcClient jdbc;
    private final LockOrder locks;
    private final Clock clock;

    /** The read-only port of u4-data-requests (the count); the anonymization needs the lock order and the clock. */
    public JdbcBotOwnerDataPort(JdbcClient jdbc) {
        this(jdbc, null, null);
    }

    @Autowired
    public JdbcBotOwnerDataPort(JdbcClient jdbc, LockOrder locks, Clock clock) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.clock = clock;
    }

    @Override
    public long countOwnerBots(UUID tenantId, UUID ownerUserId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Long count = jdbc.sql("select count(*) from bot where tenant_id = :tenant and owner_user_id = :owner")
                .param("tenant", tenantId)
                .param("owner", ownerUserId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int anonymizeOwnerBots(UUID tenantId, UUID ownerUserId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        if (locks == null || clock == null) {
            throw new IllegalStateException("The anonymization of the bots needs the lock order and the clock");
        }
        TenantScope scope = new TenantScope(tenantId, ownerUserId);
        // BR2.11 and BR5.6: the system pause (shared), the owner row, then every bot of the person ordered by id.
        locks.lockSystemPauseShared();
        if (!locks.lockOwnerIfPresent(scope)) {
            return 0;
        }
        List<Bot> owned = locks.lockOwnerBots(scope);
        if (owned.isEmpty()) {
            return 0;
        }
        List<UUID> toStop = owned.stream()
                .filter(bot -> BotStateMachine.anonymizeStop(bot.status()).changed())
                .map(Bot::botId)
                .toList();
        Timestamp now = Timestamp.from(clock.instant());
        if (!toStop.isEmpty()) {
            // BR5.3: straight to STOPPED, without a pending closing and without reasons; positions stay as they are.
            jdbc.sql("""
                            update bot set state = 'STOPPED', pause_reasons = '', stopped_at = :now, updated_at = :now,
                                           change_seq = nextval('bot_change_seq'), version = version + 1
                            where tenant_id = :tenant and owner_user_id = :owner and bot_id in (:bots)
                            """)
                    .param("now", now)
                    .param("tenant", tenantId)
                    .param("owner", ownerUserId)
                    .param("bots", toStop)
                    .update();
        }
        jdbc.sql("""
                        update bot set name = :name, updated_at = :now, change_seq = nextval('bot_change_seq'),
                                       version = version + 1
                        where tenant_id = :tenant and owner_user_id = :owner
                        """)
                .param("name", ANONYMIZED_BOT_NAME)
                .param("now", now)
                .param("tenant", tenantId)
                .param("owner", ownerUserId)
                .update();
        return toStop.size();
    }
}
