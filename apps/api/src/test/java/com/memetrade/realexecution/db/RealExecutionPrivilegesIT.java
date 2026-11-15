package com.memetrade.realexecution.db;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 6 of the U4, written BEFORE the migration 0019 (infrastructure-specification.md "Banco", scalability-design.md
 * U4-NFR3.8 and U4-NFR3.9, reliability-design.md): what the application login, the retention login and the owner may
 * do on the six tables of the REAL execution and on the new column {@code bot.market}; the triggers of the mutable
 * columns, of the transitions, of the final state (the message is cleared) and of age; the partial unique indexes of
 * BR2.2. Each statement runs as one login on its own connection.
 */
@Tag("u4-real-execution")
class RealExecutionPrivilegesIT extends AbstractPostgresIT {

    private static final String DENIED = "42501";
    private static final String UNIQUE = "23505";
    private static final String CHECK = "23514";

    private JdbcClient app;
    private JdbcClient retention;
    private UUID tenant;
    private UUID user;

    @BeforeEach
    void seed() {
        app = connect(APP);
        retention = connect(RETENTION);
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        tenant = person.tenantId();
        user = person.userId();
    }

    private static String state(JdbcClient login, String sql) {
        return sqlState(catchThrowable(() -> login.sql(sql).update()));
    }

    private UUID bot(String market) {
        UUID bot = UUID.randomUUID();
        app.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", tenant)
                .param("u", user)
                .update();
        app.sql("insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,"
                        + " created_at, updated_at" + (market == null ? "" : ", market")
                        + ") values (:b, :t, :u, 'robo', 'MOMENTUM_VOLUME', '1', 'DRAFT', now(), now()"
                        + (market == null ? "" : ", :market") + ")")
                .params(
                        market == null
                                ? Map.of("b", bot, "t", tenant, "u", user)
                                : Map.of("b", bot, "t", tenant, "u", user, "market", market))
                .update();
        return bot;
    }

    /** An order of a bot in {@code state}, created {@code age} before now(), as the application writes it. */
    private UUID order(UUID bot, String side, String state, String mint, String age) {
        UUID id = UUID.randomUUID();
        boolean buy = "BUY".equals(side);
        boolean failed = "FAILED".equals(state) || "CANCELLED".equals(state);
        boolean signed = !"PROPOSED".equals(state) && !"QUOTED".equals(state) && !failed;
        app.sql("insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key, side,"
                        + " mint, input_lamports, input_base_units, operation_mode, signing_mode, max_slippage_bps,"
                        + " reason, state, failure_code, unsigned_message, tx_signature, created_at, updated_at)"
                        + " values (:id, :t, :u, :bot, 'BOT', :key, :side, :mint, :lamports, :units, 'AUTO_FULL',"
                        + " 'AUTO_FULL', 200, 'sinal', :state, :code, 'bWVzc2FnZQ==', :sig,"
                        + " now() - cast(:age as interval), now() - cast(:age as interval))")
                .param("id", id)
                .param("t", tenant)
                .param("u", user)
                .param("bot", bot)
                .param("key", id.toString())
                .param("side", side)
                .param("mint", mint)
                .param("lamports", buy ? 1_000L : null)
                .param("units", buy ? null : 1_000L)
                .param("state", state)
                .param("code", failed ? "UPSTREAM_UNAVAILABLE" : null)
                .param("sig", signed ? SyntheticIds.signature() : null)
                .param("age", age)
                .update();
        return id;
    }

    private UUID order(UUID bot, String state) {
        return order(bot, "BUY", state, SyntheticIds.mint(), "1 minute");
    }

    private String orderState(UUID id) {
        return jdbc.sql("select state from real_order where real_order_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    @Test
    void theMarketOfABotIsPaperByDefaultAcceptsRealAndNeverChanges() {
        UUID paper = bot(null);
        UUID real = bot("REAL");

        assertThat(jdbc.sql("select market from bot where bot_id = :b")
                        .param("b", paper)
                        .query(String.class)
                        .single())
                .isEqualTo("PAPER");
        assertThat(state(app, "update bot set market = 'PAPER' where bot_id = '" + real + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "update bot set name = 'outro' where bot_id = '" + real + "'"))
                .isEqualTo("none");
        assertThat(sqlState(catchThrowable(() -> bot("SPOT")))).isEqualTo(CHECK);
    }

    @Test
    void anOrderMovesOnlyAlongTheStateMachineAndAFinalOrderNeverMoves() {
        UUID order = order(bot("REAL"), "PROPOSED");

        assertThat(state(app, "update real_order set state = 'SENT' where real_order_id = '" + order + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        app,
                        "update real_order set state = 'QUOTED', quote_at = now() where real_order_id = '" + order
                                + "'"))
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_order set state = 'CANCELLED', failure_code = 'MODE_LOWERED'"
                                + " where real_order_id = '" + order + "'"))
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_order set state = 'QUOTED', failure_code = null where real_order_id = '" + order
                                + "'"))
                .isEqualTo(DENIED);
        assertThat(orderState(order)).isEqualTo("CANCELLED");
    }

    @Test
    void reachingAFinalStateClearsTheMessageAndTheSignedTransaction() {
        UUID order = order(bot("REAL"), "SENT");
        app.sql("update real_order set signed_transaction = 'c2lnbmVk' where real_order_id = :id")
                .param("id", order)
                .update();

        app.sql("update real_order set state = 'CONFIRMED', out_base_units = 10 where real_order_id = :id")
                .param("id", order)
                .update();

        Map<String, Object> row = jdbc.sql("select unsigned_message, signed_transaction, tx_signature, out_base_units"
                        + " from real_order where real_order_id = :id")
                .param("id", order)
                .query()
                .singleRow();
        assertThat(row.get("unsigned_message")).isNull();
        assertThat(row.get("signed_transaction")).isNull();
        assertThat(row.get("tx_signature")).isNotNull();
        assertThat(row.get("out_base_units")).isEqualTo(10L);
    }

    @Test
    void theIdentityOfAnOrderNeverChangesAndTheApplicationNeverDeletesNorTruncates() {
        UUID order = order(bot("REAL"), "PROPOSED");

        assertThat(state(
                        app,
                        "update real_order set mint = '" + SyntheticIds.mint() + "' where real_order_id = '" + order
                                + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "update real_order set input_lamports = 5 where real_order_id = '" + order + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from real_order where real_order_id = '" + order + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "truncate real_order cascade")).isEqualTo(DENIED);
    }

    @Test
    void aBotHasAtMostOneOrderInProgressAndAPersonOneSalePerMint() {
        UUID bot = bot("REAL");
        order(bot, "SENT");

        assertThat(sqlState(catchThrowable(() -> order(bot, "PROPOSED")))).isEqualTo(UNIQUE);
        String mint = SyntheticIds.mint();
        order(bot("REAL"), "SELL", "AMBIGUOUS", mint, "1 minute");
        assertThat(sqlState(catchThrowable(() -> order(bot("REAL"), "SELL", "PROPOSED", mint, "1 minute"))))
                .isEqualTo(UNIQUE);
    }

    @Test
    void aFinishedOrderFreesTheBotForTheNextOne() {
        UUID bot = bot("REAL");
        order(bot, "CONFIRMED");
        order(bot, "FAILED");

        assertThat(sqlState(catchThrowable(() -> order(bot, "PROPOSED")))).isEqualTo("none");
    }

    @Test
    void theIdempotencyKeyAndTheTransactionSignatureAreUnique() {
        UUID first = order(bot("REAL"), "SENT");
        String signature = jdbc.sql("select tx_signature from real_order where real_order_id = :id")
                .param("id", first)
                .query(String.class)
                .single();
        UUID second = order(bot("REAL"), "QUOTED");

        assertThat(state(
                        app,
                        "update real_order set state = 'SIGNED', tx_signature = '" + signature
                                + "' where real_order_id = '" + second + "'"))
                .isEqualTo(UNIQUE);
        assertThat(state(
                        app,
                        "insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin,"
                                + " idempotency_key, side, mint, input_lamports, operation_mode, signing_mode,"
                                + " max_slippage_bps, reason, state, created_at, updated_at) select gen_random_uuid(),"
                                + " tenant_id, user_id, bot_id, origin, idempotency_key, side, mint, input_lamports,"
                                + " operation_mode, signing_mode, max_slippage_bps, reason, 'PROPOSED', now(), now()"
                                + " from real_order where real_order_id = '" + first + "'"))
                .isEqualTo(UNIQUE);
    }

    @Test
    void aDecisionRequestIsClosedOnceAndKeepsItsIdentity() {
        UUID bot = bot("REAL");
        UUID request = UUID.randomUUID();
        app.sql("insert into real_decision_request (decision_request_id, tenant_id, user_id, bot_id, decision_id,"
                        + " approval_id, side, mint, input_lamports, reason, created_at) values (:id, :t, :u, :b,"
                        + " :d, :a, 'BUY', :m, 100, 'sinal', now())")
                .param("id", request)
                .param("t", tenant)
                .param("u", user)
                .param("b", bot)
                .param("d", UUID.randomUUID())
                .param("a", UUID.randomUUID())
                .param("m", SyntheticIds.mint())
                .update();

        assertThat(state(
                        app,
                        "update real_decision_request set bot_id = gen_random_uuid() where" + " decision_request_id = '"
                                + request + "'"))
                .isEqualTo(DENIED);
        assertThat(state(
                        app,
                        "update real_decision_request set state = 'PROCESSED', processed_at = now() where"
                                + " decision_request_id = '" + request + "'"))
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_decision_request set state = 'DISCARDED', discard_reason ="
                                + " 'ORDER_IN_PROGRESS' where decision_request_id = '" + request + "'"))
                .isEqualTo(DENIED);
        assertThat(state(app, "delete from real_decision_request where decision_request_id = '" + request + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void anAttemptRecordsItsOutcomeOnceAndALiquidationIsOneActivePerPerson() {
        UUID order = order(bot("REAL"), "SENT");
        UUID attempt = UUID.randomUUID();
        app.sql("insert into real_order_attempt (attempt_id, real_order_id, tenant_id, sent_at) values (:a, :o, :t,"
                        + " now())")
                .param("a", attempt)
                .param("o", order)
                .param("t", tenant)
                .update();

        assertThat(state(
                        app,
                        "update real_order_attempt set outcome = 'ACCEPTED', resolved_at = now() where"
                                + " attempt_id = '" + attempt + "'"))
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_order_attempt set outcome = 'RPC_ERROR' where attempt_id = '" + attempt + "'"))
                .isEqualTo(DENIED);

        String insertLiquidation = "insert into real_liquidation (liquidation_id, tenant_id, user_id, state,"
                + " created_at, updated_at) values (gen_random_uuid(), '" + tenant + "', '" + user
                + "', 'SELLING', now(), now())";
        assertThat(state(app, insertLiquidation)).isEqualTo("none");
        assertThat(state(app, insertLiquidation)).isEqualTo(UNIQUE);
        assertThat(state(app, "update real_liquidation set state = 'DONE' where user_id = '" + user + "'"))
                .as("SELLING goes to WITHDRAWING first")
                .isEqualTo(DENIED);
    }

    @Test
    void theRetentionLoginDeletesOnlyOldRowsAndNeverTheHealthOfTheProviders() {
        UUID oldOrder = order(bot("REAL"), "BUY", "CONFIRMED", SyntheticIds.mint(), "5 years 1 day");
        UUID youngOrder = order(bot("REAL"), "BUY", "CONFIRMED", SyntheticIds.mint(), "4 years");

        assertThat(state(retention, "delete from real_order where real_order_id = '" + oldOrder + "'"))
                .isEqualTo("none");
        assertThat(state(retention, "delete from real_order where real_order_id = '" + youngOrder + "'"))
                .isEqualTo(DENIED);
        assertThat(state(retention, "select * from real_upstream_health")).isEqualTo(DENIED);
        assertThat(state(
                        retention, "update real_order set state = 'FAILED' where real_order_id = '" + youngOrder + "'"))
                .isEqualTo(DENIED);
    }

    @Test
    void theApplicationKeepsTheHealthOfTheProvidersAndNeverTouchesThePurgeRecord() {
        assertThat(state(
                        app,
                        "insert into real_upstream_health (subject_id, provider, tenant_id,"
                                + " consecutive_failures, updated_at) values ('" + user + "', 'JUPITER', '" + tenant
                                + "', 1, now())"))
                .isEqualTo("none");
        assertThat(state(
                        app,
                        "update real_upstream_health set consecutive_failures = 2 where subject_id = '" + user + "'"))
                .isEqualTo("none");
        assertThat(state(app, "select * from real_execution_purge_run")).isEqualTo(DENIED);
        assertThat(state(
                        retention,
                        "insert into real_execution_purge_run (run_id, started_at, finished_at, cutoff,"
                                + " deleted_count, batches, result) values (gen_random_uuid(), now(), now(), now(), 0, 0,"
                                + " 'SUCCEEDED')"))
                .isEqualTo("none");
        assertThat(state(retention, "update real_execution_purge_run set deleted_count = 1"))
                .isEqualTo(DENIED);
    }

    @Test
    void aClosedDecisionIsPurgedAfterThirteenMonthsAndAPendingOneNever() {
        UUID bot = bot("REAL");
        String insert = "insert into real_decision_request (decision_request_id, tenant_id, user_id, bot_id,"
                + " decision_id, side, mint, token_base_units, reason, state, discard_reason, created_at,"
                + " processed_at) values (:id, :t, :u, :b, gen_random_uuid(), 'SELL', :m, 5, 'sinal', :state, :why,"
                + " now() - interval '14 months', :processed)";
        UUID closed = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        jdbc.sql(insert)
                .param("id", closed)
                .param("t", tenant)
                .param("u", user)
                .param("b", bot)
                .param("m", SyntheticIds.mint())
                .param("state", "DISCARDED")
                .param("why", "ORDER_IN_PROGRESS")
                .param("processed", java.sql.Timestamp.from(clock.instant().minusSeconds(400L * 86_400)))
                .update();
        jdbc.sql(insert)
                .param("id", pending)
                .param("t", tenant)
                .param("u", user)
                .param("b", bot)
                .param("m", SyntheticIds.mint())
                .param("state", "PENDING")
                .param("why", null)
                .param("processed", null)
                .update();

        assertThat(state(retention, "delete from real_decision_request where decision_request_id = '" + closed + "'"))
                .isEqualTo("none");
        assertThat(state(retention, "delete from real_decision_request where decision_request_id = '" + pending + "'"))
                .isEqualTo(DENIED);
    }
}
