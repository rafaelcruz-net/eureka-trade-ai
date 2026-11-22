package com.memetrade.bots.infra;

import com.memetrade.bots.domain.BotRef;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code bot_cycle} rows (BR3.2, BR3.5): one per bot and 5-minute slot. The RUNNING row is inserted at the start
 * of the cycle transaction by an insert that never aborts on conflict (it waits for a concurrent one to finish) and is
 * completed in the same transaction; after a rollback, the FAILED row is written in a transaction of its own.
 */
@Repository
public class JdbcCycleRepository {

    public enum Status {
        RUNNING,
        COMPLETED,
        SKIPPED,
        FAILED
    }

    private final JdbcClient jdbc;

    public JdbcCycleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** BR3.2: claims the slot of the bot; false when the slot already has a cycle (never processed twice). */
    public boolean tryStart(UUID cycleId, BotRef bot, Instant slotStart, Instant now) {
        return jdbc.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status,
                                               started_at)
                        values (:cycle, :bot, :tenant, :owner, :slot, 'RUNNING', :now)
                        on conflict (bot_id, slot_start) do nothing
                        """)
                        .param("cycle", cycleId)
                        .param("bot", bot.botId())
                        .param("tenant", bot.scope().tenantId())
                        .param("owner", bot.scope().ownerUserId())
                        .param("slot", Timestamp.from(slotStart))
                        .param("now", Timestamp.from(now))
                        .update()
                == 1;
    }

    /**
     * 261113-trading-real-bot U4 (test strategy of the recorded swap): whether a committed cycle of the bot ended with
     * {@code outcome} (for example {@code REAL_RECORDED}).
     */
    /** True when any cycle of the bot was ever recorded, whatever its status (read by a test-profile helper only). */
    public boolean hasAnyCycle(UUID botId) {
        return jdbc.sql("select exists (select 1 from bot_cycle where bot_id = :bot)")
                .param("bot", botId)
                .query(Boolean.class)
                .single();
    }

    public boolean hasOutcome(UUID botId, String outcome) {
        return jdbc.sql("select exists (select 1 from bot_cycle where bot_id = :bot and outcome = :outcome)")
                .param("bot", botId)
                .param("outcome", outcome)
                .query(Boolean.class)
                .single();
    }

    /** The final status of the cycle, in the cycle transaction (RUNNING never stays after the commit). */
    public void finish(
            UUID cycleId,
            Status status,
            Optional<String> outcome,
            Optional<String> skipReason,
            Optional<UUID> decisionId,
            Instant finishedAt) {
        jdbc.sql("""
                        update bot_cycle set status = :status, outcome = :outcome, skip_reason = :skip,
                                             decision_id = :decision, finished_at = :finished
                        where cycle_id = :cycle
                        """)
                .param("status", status.name())
                .param("outcome", outcome.orElse(null), java.sql.Types.VARCHAR)
                .param("skip", skipReason.orElse(null), java.sql.Types.VARCHAR)
                .param("decision", decisionId.orElse(null), java.sql.Types.OTHER)
                .param("finished", Timestamp.from(finishedAt))
                .param("cycle", cycleId)
                .update();
    }

    /** BR3.5: the FAILED slot, after the rollback, in its own transaction; never overwrites a recorded slot. */
    public boolean recordFailed(UUID cycleId, BotRef bot, Instant slotStart, String cause, Instant now) {
        return jdbc.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status,
                                               failure_cause, started_at, finished_at)
                        values (:cycle, :bot, :tenant, :owner, :slot, 'FAILED', :cause, :now, :now)
                        on conflict (bot_id, slot_start) do nothing
                        """)
                        .param("cycle", cycleId)
                        .param("bot", bot.botId())
                        .param("tenant", bot.scope().tenantId())
                        .param("owner", bot.scope().ownerUserId())
                        .param("slot", Timestamp.from(slotStart))
                        .param("cause", cause.length() > 200 ? cause.substring(0, 200) : cause)
                        .param("now", Timestamp.from(now))
                        .update()
                == 1;
    }

    /** NFR9.1: the end of the last finished cycle of any bot. */
    public Optional<Instant> lastFinished() {
        return jdbc
                .sql("select max(finished_at) from bot_cycle where status in ('COMPLETED', 'SKIPPED', 'FAILED')")
                .query(Timestamp.class)
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(Timestamp::toInstant);
    }

    /**
     * BR9.1: one batch of old slots without a decision (skipped or failed) before {@code cutoff}; HOLD and NO_TRADE
     * slots leave with their decision (see the decision purge). Returns how many were deleted.
     */
    public int purgeWithoutDecision(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                        delete from bot_cycle where cycle_id in (
                            select c.cycle_id from bot_cycle c
                            where c.slot_start < :cutoff and c.decision_id is null
                              and not exists (select 1 from decision d where d.cycle_id = c.cycle_id)
                            limit :batch)
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("batch", batchSize)
                .update();
    }
}
