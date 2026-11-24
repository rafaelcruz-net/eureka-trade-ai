package com.memetrade.realexecution;

import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U4 (test support of the Etapas 3 to 5): REAL orders and decision requests of a bot written
 * straight in the database of the test, in any state, the way the execution leaves them (an order SIGNED, SENT or
 * AMBIGUOUS has its signature; FAILED and CANCELLED have a failure code; a PENDING decision has no processing instant).
 * Synthetic mints and signatures only; never a real wallet.
 */
public final class OpenSellsFixture {

    private final JdbcClient jdbc;

    public OpenSellsFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An order of {@code botId} in {@code state}: a SELL of {@code amount} base units, or a BUY of as many lamports. */
    public UUID order(UUID tenantId, UUID userId, UUID botId, String side, String mint, long amount, String state) {
        UUID id = UUID.randomUUID();
        boolean signed =
                switch (state) {
                    case "SIGNED", "SENT", "AMBIGUOUS", "CONFIRMED" -> true;
                    default -> false;
                };
        String failure = "FAILED".equals(state) ? "CHAIN_REJECTED" : "CANCELLED".equals(state) ? "PAUSED" : null;
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                          side, mint, input_lamports, input_base_units, operation_mode, signing_mode, max_slippage_bps,
                          reason, state, failure_code, tx_signature, created_at, updated_at)
                        values (:id, :t, :u, :b, 'BOT', :key, :side, :mint, :lamports, :units, 'AUTO_FULL', 'AUTO_FULL',
                          100, 'teste', :state, :failure, :sig, :at, :at)""")
                .param("id", id)
                .param("t", tenantId)
                .param("u", userId)
                .param("b", botId)
                .param("key", "open-sells:" + id)
                .param("side", side)
                .param("mint", mint)
                .param("lamports", "BUY".equals(side) ? amount : null)
                .param("units", "SELL".equals(side) ? amount : null)
                .param("state", state)
                .param("failure", failure)
                .param("sig", signed || "FAILED".equals(state) ? SyntheticIds.signature() : null)
                .param("at", Timestamp.from(Instant.now()))
                .update();
        return id;
    }

    /** A decision request of {@code botId} in {@code state}, eligible from {@code notBefore}. */
    public UUID decision(
            UUID tenantId,
            UUID userId,
            UUID botId,
            String side,
            String mint,
            long amount,
            String state,
            Instant notBefore) {
        UUID id = UUID.randomUUID();
        boolean buy = "BUY".equals(side);
        jdbc.sql("""
                        insert into real_decision_request (decision_request_id, tenant_id, user_id, bot_id, decision_id,
                          approval_id, side, mint, input_lamports, token_base_units, reason, state, discard_reason,
                          created_at, processed_at, not_before)
                        values (:id, :t, :u, :b, :decision, :approval, :side, :mint, :lamports, :units, 'teste', :state,
                          :discard, :at, :processed, :notBefore)""")
                .param("id", id)
                .param("t", tenantId)
                .param("u", userId)
                .param("b", botId)
                .param("decision", UUID.randomUUID())
                .param("approval", buy ? UUID.randomUUID() : null)
                .param("side", side)
                .param("mint", mint)
                .param("lamports", buy ? amount : null)
                .param("units", buy ? null : amount)
                .param("state", state)
                .param("discard", "DISCARDED".equals(state) ? "STATE_CHANGED" : null)
                .param("at", Timestamp.from(Instant.now()))
                .param("processed", "PENDING".equals(state) ? null : Timestamp.from(Instant.now()))
                .param("notBefore", Timestamp.from(notBefore))
                .update();
        return id;
    }

    /** A PENDING SELL decision of {@code botId}, eligible now. */
    public UUID pendingSell(UUID tenantId, UUID userId, UUID botId, String mint, long units) {
        return decision(tenantId, userId, botId, "SELL", mint, units, "PENDING", Instant.now());
    }
}
