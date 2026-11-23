package com.memetrade.pnl;

import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U2 (test support of Etapas 20 to 22): settled REAL orders and their ledger rows written the
 * way the confirmations write them ({@code RealOrderLedger}: reference {@code order:<id>}, keys {@code <id>:<KIND>},
 * the signature on every row, the slot in {@code real_entry_slot}), straight in the database of the test, for one person
 * and one bot wallet. Synthetic mints and signatures only; never a real wallet.
 */
public final class RealPnlSeed {

    private final JdbcClient jdbc;
    private final UUID tenantId;
    private final UUID userId;
    private final UUID botWalletId;
    private Instant at;
    private long slot;

    public RealPnlSeed(JdbcClient jdbc, UUID tenantId, UUID userId, UUID botWalletId, Instant start, long firstSlot) {
        this.jdbc = jdbc;
        this.tenantId = tenantId;
        this.userId = userId;
        this.botWalletId = botWalletId;
        this.at = start;
        this.slot = firstSlot;
    }

    /** A confirmed buy of {@code botId}, its fee paid by the bot wallet; returns the order id. */
    public UUID buy(UUID botId, String mint, long units, long paid, long fee) {
        UUID order = order(botId, null, "BUY", mint, paid, units, "CONFIRMED", fee, "BOT_WALLET");
        String sig = signatureOf(order);
        row("SWAP_FILL_IN", -paid, mint, units, sig, order, "SWAP_FILL_IN");
        if (fee > 0) {
            row("NETWORK_FEE", -fee, null, null, sig, order, "NETWORK_FEE");
        }
        slot(sig);
        advance();
        return order;
    }

    /** A confirmed own sale of {@code botId}. */
    public UUID sell(UUID botId, String mint, long units, long received, long fee) {
        UUID order = order(botId, null, "SELL", mint, units, received, "CONFIRMED", fee, "BOT_WALLET");
        String sig = signatureOf(order);
        row("SWAP_FILL_OUT", received, mint, units, sig, order, "SWAP_FILL_OUT");
        if (fee > 0) {
            row("NETWORK_FEE", -fee, null, null, sig, order, "NETWORK_FEE");
        }
        slot(sig);
        advance();
        return order;
    }

    /** A confirmed sale of the "vender tudo e sacar" (no bot). */
    public UUID liquidation(String mint, long units, long received, long fee) {
        UUID order = order(null, UUID.randomUUID(), "SELL", mint, units, received, "CONFIRMED", fee, "BOT_WALLET");
        String sig = signatureOf(order);
        row("SWAP_FILL_OUT", received, mint, units, sig, order, "SWAP_FILL_OUT");
        if (fee > 0) {
            row("NETWORK_FEE", -fee, null, null, sig, order, "NETWORK_FEE");
        }
        slot(sig);
        advance();
        return order;
    }

    /** A settled order of {@code botId} with no ledger row (the attribution of a bot that is not the person's). */
    public UUID orderOnly(UUID botId, String mint) {
        UUID order = order(botId, null, "BUY", mint, 100, 1, "CONFIRMED", 0, "BOT_WALLET");
        advance();
        return order;
    }

    /** {@code count} settled failed orders of {@code botId} in one statement (the cap of real-max-orders). */
    public void failedOrders(UUID botId, String mint, int count) {
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key, side,
                          mint, input_lamports, operation_mode, signing_mode, max_slippage_bps, reason, state,
                          failure_code, tx_signature, fee_lamports, fee_payer, created_at, updated_at)
                        select gen_random_uuid(), :t, :u, :b, 'BOT', 'cap-' || g || '-' || :salt, 'BUY', :mint, 100,
                          'AUTO_FULL', 'AUTO_FULL', 100, 'teste', 'FAILED', 'CHAIN_REJECTED',
                          'capsig' || g || :salt, 0, 'BOT_WALLET', :at, :at
                        from generate_series(1, :n) g""")
                .param("t", tenantId)
                .param("u", userId)
                .param("b", botId)
                .param("salt", UUID.randomUUID().toString().substring(0, 8))
                .param("mint", mint)
                .param("at", Timestamp.from(at))
                .param("n", count)
                .update();
    }

    /**
     * The newest event REAL_PNL_RECONCILIATION_MISMATCH of the person in the outbox. The write runs on the executor of the
     * audit gate and may still be finishing when the block already answered (its first connection is opened lazily), so
     * the read waits up to 3 s for it.
     */
    public static String mismatchEvent(JdbcClient jdbc, UUID userId) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            java.util.Optional<String> event = jdbc.sql("""
                            select payload::text from audit_outbox
                            where payload ->> 'action' = 'REAL_PNL_RECONCILIATION_MISMATCH'
                              and payload ->> 'actor' = :actor
                            order by created_at desc limit 1""")
                    .param("actor", userId.toString())
                    .query(String.class)
                    .optional();
            if (event.isPresent()) {
                return event.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no REAL_PNL_RECONCILIATION_MISMATCH of the person in 3 s");
    }

    private UUID order(
            UUID botId,
            UUID liquidationId,
            String side,
            String mint,
            long input,
            long out,
            String state,
            long fee,
            String feePayer) {
        UUID order = UUID.randomUUID();
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_wallet_id, bot_id, liquidation_id,
                          origin, idempotency_key, side, mint, input_lamports, input_base_units, operation_mode,
                          signing_mode, max_slippage_bps, reason, state, tx_signature, out_base_units, fee_lamports,
                          fee_payer, created_at, updated_at)
                        values (:id, :t, :u, :w, :b, :l, :origin, :key, :side, :mint, :lamports, :units, 'AUTO_FULL',
                          'AUTO_FULL', 100, 'teste', :state, :sig, :out, :fee, :payer, :at, :at)""")
                .param("id", order)
                .param("t", tenantId)
                .param("u", userId)
                .param("w", botWalletId)
                .param("b", botId)
                .param("l", liquidationId)
                .param("origin", botId == null ? "USER_LIQUIDATION" : "BOT")
                .param("key", "seed:" + order)
                .param("side", side)
                .param("mint", mint)
                .param("lamports", "BUY".equals(side) ? input : null)
                .param("units", "SELL".equals(side) ? input : null)
                .param("state", state)
                .param("sig", SyntheticIds.signature())
                .param("out", out)
                .param("fee", fee)
                .param("payer", feePayer)
                .param("at", Timestamp.from(at))
                .update();
        return order;
    }

    private String signatureOf(UUID order) {
        return jdbc.sql("select tx_signature from real_order where real_order_id = :id")
                .param("id", order)
                .query(String.class)
                .single();
    }

    private void row(String kind, long lamports, String mint, Long units, String sig, UUID order, String suffix) {
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, mint,
                          token_base_units, tx_signature, reference, idempotency_key, created_at)
                        values (gen_random_uuid(), :t, :w, :kind, :lamports, :mint, :units, :sig, :ref, :key, :at)""")
                .param("t", tenantId)
                .param("w", botWalletId)
                .param("kind", kind)
                .param("lamports", lamports)
                .param("mint", mint)
                .param("units", units)
                .param("sig", sig)
                .param("ref", "order:" + order)
                .param("key", order + ":" + suffix)
                .param("at", Timestamp.from(at))
                .update();
    }

    private void slot(String sig) {
        jdbc.sql("insert into real_entry_slot (tx_signature, slot, recorded_at) values (:sig, :slot, :at)")
                .param("sig", sig)
                .param("slot", slot++)
                .param("at", Timestamp.from(at))
                .update();
    }

    private void advance() {
        at = at.plus(Duration.ofMinutes(1));
    }
}
