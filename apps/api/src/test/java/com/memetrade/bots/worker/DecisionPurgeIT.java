package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BR9.1 (Passo 11, written BEFORE the purge job; NFR10.5): HOLD and NO_TRADE decisions older than 30 days are deleted
 * with their cycles, in batches; BUY and SELL stay for the whole beta; recent rows and the current slot are never
 * touched; a repeated round deletes nothing more.
 */
class DecisionPurgeIT extends AbstractU5WorkerIT {

    @Autowired
    private DecisionPurgeJob purge;

    @Test
    void oldHoldAndNoTradeDecisionsLeaveWithTheirCyclesAndOrdersStay() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        Instant now = clock.instant();
        UUID oldNoTrade = decision(botId, now.minus(Duration.ofDays(31)), "NO_TRADE");
        UUID oldHold = decision(botId, now.minus(Duration.ofDays(30)).minusSeconds(1), "HOLD");
        UUID oldBuy = decision(botId, now.minus(Duration.ofDays(40)), "BUY");
        UUID recent = decision(botId, now.minus(Duration.ofDays(29)), "NO_TRADE");
        UUID oldSkipped = skippedCycle(botId, now.minus(Duration.ofDays(32)));

        DecisionPurgeJob.Round round = purge.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(exists("decision", "decision_id", oldNoTrade)).isFalse();
        assertThat(exists("decision", "decision_id", oldHold)).isFalse();
        assertThat(exists("decision", "decision_id", oldBuy)).isTrue();
        assertThat(exists("decision", "decision_id", recent)).isTrue();
        assertThat(exists("bot_cycle", "cycle_id", oldSkipped)).isFalse();
        assertThat(purge.runOnce().decisions()).isZero();
    }

    @Test
    void theCurrentSlotIsNeverTouched() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        UUID current = skippedCycle(botId, clock.instant());

        purge.runOnce();

        assertThat(exists("bot_cycle", "cycle_id", current)).isTrue();
    }

    // ----- helpers (synthetic rows) -----

    private UUID decision(UUID botId, Instant at, String action) {
        UUID cycle = UUID.randomUUID();
        UUID decision = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status,
                                               outcome, decision_id, started_at, finished_at)
                        select :cycle, bot_id, tenant_id, owner_user_id, :at, 'COMPLETED', 'NO_ORDER', :decision, :at,
                               :at
                        from bot where bot_id = :bot
                        """)
                .param("cycle", cycle)
                .param("decision", decision)
                .param("bot", botId)
                .param("at", Timestamp.from(at))
                .update();
        boolean order = action.equals("BUY") || action.equals("SELL");
        jdbc.sql("""
                        insert into decision (decision_id, bot_id, cycle_id, tenant_id, owner_user_id, mode, chain,
                            token_mint, action, no_trade_reason, requested_quantity, strategy_template_id,
                            strategy_version, parameters, limits_version, factors, inputs, context, summary,
                            valid_until, created_at)
                        select :decision, bot_id, :cycle, tenant_id, owner_user_id, 'PAPER', 'solana', 'MintPurge',
                               :action, :reason, :quantity, 'MOMENTUM_VOLUME', 'v1', '{}'::jsonb, 1, '[]'::jsonb,
                               '[]'::jsonb, '{}'::jsonb, 'Teste', :at, :at
                        from bot where bot_id = :bot
                        """)
                .param("decision", decision)
                .param("cycle", cycle)
                .param("bot", botId)
                .param("action", action)
                .param("reason", action.equals("NO_TRADE") ? "NO_SIGNAL" : null)
                .param("quantity", order ? java.math.BigDecimal.ONE : null)
                .param("at", Timestamp.from(at))
                .update();
        return decision;
    }

    private UUID skippedCycle(UUID botId, Instant slot) {
        UUID cycle = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status,
                                               skip_reason, started_at, finished_at)
                        select :cycle, bot_id, tenant_id, owner_user_id, :slot, 'SKIPPED', 'PAUSED', :slot, :slot
                        from bot where bot_id = :bot
                        """)
                .param("cycle", cycle)
                .param("bot", botId)
                .param("slot", Timestamp.from(slot))
                .update();
        return cycle;
    }

    private boolean exists(String table, String column, UUID id) {
        return jdbc.sql("select count(*) from " + table + " where " + column + " = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
