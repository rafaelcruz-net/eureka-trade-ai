package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes rows of the U6 tables directly through one login, for the database tests (privileges, triggers, purge).
 * Every value is synthetic; {@code evaluated_at} is explicit because the purge cut is the database {@code now()}.
 */
public final class RiskRows {

    private final JdbcClient jdbc;

    public RiskRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One evaluation of a new decision, {@code age} before the database {@code now()} (for example "32 days"). */
    public UUID evaluation(Principal person, UUID botId, String outcome, String age) {
        return evaluation(person, botId, UUID.randomUUID(), outcome, age, "PAPER");
    }

    public UUID evaluation(Principal person, UUID botId, UUID decisionId, String outcome, String age, String mode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into order_evaluation (evaluation_id, tenant_id, owner_user_id, mode, bot_id, decision_id,
                            side, chain, token_mint, requested_quantity, limits_version, outcome, evaluated_at)
                        values (:id, :tenant, :owner, :mode, :bot, :decision, 'BUY', 'solana', :mint, 100.000000, 1,
                            :outcome, now() - cast(:age as interval))
                        """)
                .param("id", id)
                .param("tenant", person.tenantId())
                .param("owner", person.userId())
                .param("mode", mode)
                .param("bot", botId)
                .param("decision", decisionId)
                .param("mint", SyntheticIds.mint())
                .param("outcome", outcome)
                .param("age", age)
                .update();
        return id;
    }

    /** One rejection citing {@code evaluationId}, with one detail of its first reason. */
    public UUID rejection(Principal person, UUID botId, UUID evaluationId, String reasons) {
        return rejection(person, botId, evaluationId, reasons, "PAPER");
    }

    public UUID rejection(Principal person, UUID botId, UUID evaluationId, String reasons, String mode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into order_rejection (rejection_id, tenant_id, owner_user_id, mode, bot_id, decision_id,
                            evaluation_id, side, chain, token_mint, requested_quantity, reasons, pause_triggers,
                            limits_version, market, evaluated_at)
                        values (:id, :tenant, :owner, :mode, :bot, :decision, :evaluation, 'BUY', 'solana', :mint,
                            100.000000, :reasons, '', 1, cast(:market as jsonb), now())
                        """)
                .param("id", id)
                .param("tenant", person.tenantId())
                .param("owner", person.userId())
                .param("mode", mode)
                .param("bot", botId)
                .param("decision", UUID.randomUUID())
                .param("evaluation", evaluationId)
                .param("mint", SyntheticIds.mint())
                .param("reasons", reasons)
                .param("market", MARKET)
                .update();
        return id;
    }

    public void detail(Principal person, UUID rejectionId, String reason, String mode) {
        jdbc.sql("""
                        insert into rejection_reason_detail (rejection_id, tenant_id, owner_user_id, mode, reason,
                            measured, limit_value, unit)
                        values (:rejection, :tenant, :owner, :mode, :reason, '100.000001', '100.000000', 'USDC')
                        """)
                .param("rejection", rejectionId)
                .param("tenant", person.tenantId())
                .param("owner", person.userId())
                .param("mode", mode)
                .param("reason", reason)
                .update();
    }

    /** One row of the purge run log, started {@code age} before the database {@code now()}. */
    public UUID purgeRun(String age, String result) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into risk_purge_run (run_id, started_at, finished_at, cutoff, deleted_count, batches,
                            result, failure_cause)
                        values (:id, now() - cast(:age as interval), now() - cast(:age as interval),
                            now() - cast(:age as interval) - interval '31 days', 0, 0, :result, null)
                        """).param("id", id).param("age", age).param("result", result).update();
        return id;
    }

    public static Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }

    /** A recorded MarketInputs document with every value known (the shape written by the gate). */
    public static final String MARKET = """
            {"referencePrice":{"status":"KNOWN","value":"1.000000000000","sourceId":"sim-u7","sourceName":"Simulado U7",\
            "simulated":true,"observedAt":"2026-10-28T12:00:00Z","stale":false},\
            "liquidity":{"status":"KNOWN","value":"10000.000000","sourceId":"sim-u7","sourceName":"Simulado U7",\
            "simulated":true,"observedAt":"2026-10-28T12:00:00Z","stale":false},\
            "estimatedSlippagePct":"1.5000","orderValue":"100.000000"}""";
}
