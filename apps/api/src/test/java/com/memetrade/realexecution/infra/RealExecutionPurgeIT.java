package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.infra.RiskRetentionDataSource;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 14 of the U4, written BEFORE the code (U4-NFR3.9, scalability-design.md, infrastructure-specification.md): the
 * daily purge of the REAL execution deletes, through the retention pool, the orders, the sends and the liquidations older
 * than 5 years and the closed decisions older than 13 months. It never deletes a recent row, a PENDING decision, an
 * order still in progress, a liquidation still active or an order whose send is younger (the foreign key); a wrong
 * selection is refused by the age triggers and the round is recorded as FAILED; every round is recorded in
 * {@code real_execution_purge_run}, that log is cleaned after 90 days and a successful round counts the job
 * {@code purge}.
 */
@Tag("u4-real-execution")
class RealExecutionPurgeIT extends AbstractPostgresIT {

    @Autowired
    private RealExecutionPurgeJob purge;

    @Autowired
    private RiskRetentionDataSource retentionPool;

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private RealExecutionMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Clock appClock;

    private JdbcClient app;
    private UUID tenant;
    private UUID user;
    private UUID bot;

    @BeforeEach
    void seed() {
        app = connect(APP);
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        tenant = person.tenantId();
        user = person.userId();
        bot = UUID.randomUUID();
        app.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", tenant)
                .param("u", user)
                .update();
        app.sql("insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,"
                        + " created_at, updated_at, market) values (:b, :t, :u, 'robo', 'MOMENTUM_VOLUME', '1',"
                        + " 'DRAFT', now(), now(), 'REAL')")
                .param("b", bot)
                .param("t", tenant)
                .param("u", user)
                .update();
    }

    private UUID order(String state, String age) {
        UUID id = UUID.randomUUID();
        boolean failed = "FAILED".equals(state) || "CANCELLED".equals(state);
        boolean signed = !"PROPOSED".equals(state) && !"QUOTED".equals(state) && !failed;
        app.sql("insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key, side,"
                        + " mint, input_lamports, operation_mode, signing_mode, max_slippage_bps, reason, state,"
                        + " failure_code, unsigned_message, tx_signature, created_at, updated_at) values (:id, :t, :u,"
                        + " :bot, 'BOT', :key, 'BUY', :mint, 1000, 'AUTO_FULL', 'AUTO_FULL', 200, 'sinal', :state,"
                        + " :code, 'bWVzc2FnZQ==', :sig, now() - cast(:age as interval), now() - cast(:age as"
                        + " interval))")
                .param("id", id)
                .param("t", tenant)
                .param("u", user)
                .param("bot", bot)
                .param("key", id.toString())
                .param("mint", SyntheticIds.mint())
                .param("state", state)
                .param("code", failed ? "UPSTREAM_UNAVAILABLE" : null)
                .param("sig", signed ? SyntheticIds.signature() : null)
                .param("age", age)
                .update();
        return id;
    }

    private UUID attempt(UUID order, String age) {
        UUID id = UUID.randomUUID();
        app.sql("insert into real_order_attempt (attempt_id, real_order_id, tenant_id, sent_at) values (:a, :o, :t,"
                        + " now() - cast(:age as interval))")
                .param("a", id)
                .param("o", order)
                .param("t", tenant)
                .param("age", age)
                .update();
        return id;
    }

    private UUID liquidation(String state, String age) {
        UUID id = UUID.randomUUID();
        app.sql("insert into real_liquidation (liquidation_id, tenant_id, user_id, state, created_at, updated_at)"
                        + " values (:id, :t, :u, :state, now() - cast(:age as interval), now() - cast(:age as"
                        + " interval))")
                .param("id", id)
                .param("t", tenant)
                .param("u", user)
                .param("state", state)
                .param("age", age)
                .update();
        return id;
    }

    private UUID decision(String state, String age) {
        UUID id = UUID.randomUUID();
        boolean pending = "PENDING".equals(state);
        app.sql("insert into real_decision_request (decision_request_id, tenant_id, user_id, bot_id, decision_id, side,"
                        + " mint, token_base_units, reason, state, discard_reason, created_at, processed_at) values"
                        + " (:id, :t, :u, :b, gen_random_uuid(), 'SELL', :m, 5, 'sinal', :state, :why,"
                        + " now() - cast(:age as interval), "
                        + (pending ? "null" : "now() - cast(:age as interval)") + ")")
                .param("id", id)
                .param("t", tenant)
                .param("u", user)
                .param("b", bot)
                .param("m", SyntheticIds.mint())
                .param("state", state)
                .param("why", "DISCARDED".equals(state) ? "STATE_CHANGED" : null)
                .param("age", age)
                .update();
        return id;
    }

    private boolean exists(String table, String column, UUID id) {
        return jdbc.sql("select exists (select 1 from " + table + " where " + column + " = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private String recorded(UUID runId) {
        return connect(RETENTION)
                .sql("select result from real_execution_purge_run where run_id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    @Test
    void theRoundDeletesOnlyFinalRowsOlderThan5YearsAndClosedDecisionsOlderThan13Months() {
        UUID oldOrder = order("CONFIRMED", "5 years 2 days");
        UUID oldAttempt = attempt(oldOrder, "5 years 2 days");
        UUID recentOrder = order("CONFIRMED", "4 years");
        UUID oldInProgress = order("SENT", "5 years 2 days");
        UUID oldDone = liquidation("DONE", "5 years 2 days");
        UUID recentDone = liquidation("PARTIAL", "1 year");
        UUID oldDecision = decision("PROCESSED", "14 months");
        UUID oldDiscarded = decision("DISCARDED", "14 months");
        UUID recentDecision = decision("PROCESSED", "12 months");
        UUID oldPending = decision("PENDING", "14 months");

        RealExecutionPurgeJob.Round round = purge.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(round.deleted()).isGreaterThanOrEqualTo(5);
        assertThat(exists("real_order_attempt", "attempt_id", oldAttempt)).isFalse();
        assertThat(exists("real_order", "real_order_id", oldOrder)).isFalse();
        assertThat(exists("real_order", "real_order_id", recentOrder)).isTrue();
        assertThat(exists("real_order", "real_order_id", oldInProgress))
                .as("an order still in progress is never purged")
                .isTrue();
        assertThat(exists("real_liquidation", "liquidation_id", oldDone)).isFalse();
        assertThat(exists("real_liquidation", "liquidation_id", recentDone)).isTrue();
        assertThat(exists("real_decision_request", "decision_request_id", oldDecision))
                .isFalse();
        assertThat(exists("real_decision_request", "decision_request_id", oldDiscarded))
                .isFalse();
        assertThat(exists("real_decision_request", "decision_request_id", recentDecision))
                .isTrue();
        assertThat(exists("real_decision_request", "decision_request_id", oldPending))
                .as("a PENDING decision is never purged")
                .isTrue();
    }

    /** The send references the order: an old order whose send is still young waits for it (foreign key). */
    @Test
    void anOldOrderWithAYoungerSendIsKeptUntilTheSendAges() {
        UUID order = order("CONFIRMED", "5 years 2 days");
        UUID young = attempt(order, "4 years");

        RealExecutionPurgeJob.Round round = purge.runOnce();

        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(exists("real_order", "real_order_id", order)).isTrue();
        assertThat(exists("real_order_attempt", "attempt_id", young)).isTrue();
    }

    @Test
    void aSecondRoundDeletesNothingMore() {
        order("FAILED", "5 years 2 days");
        decision("PROCESSED", "14 months");
        purge.runOnce();

        RealExecutionPurgeJob.Round again = purge.runOnce();

        assertThat(again.result()).isEqualTo("SUCCEEDED");
        assertThat(again.deleted()).isZero();
    }

    @Test
    void everyRoundIsRecordedAndCountsTheJobPurge() {
        double before =
                meters.counter("real_execution_job_runs", "job", "purge").count();

        RealExecutionPurgeJob.Round round = purge.runOnce();

        assertThat(recorded(round.runId().orElseThrow())).isEqualTo("SUCCEEDED");
        assertThat(meters.counter("real_execution_job_runs", "job", "purge").count())
                .isEqualTo(before + 1);
    }

    /** U4-NFR3.9: even a selection that reaches a recent row deletes nothing: the age trigger refuses it. */
    @Test
    void aWrongSelectionNeverDeletesARecentRowAndTheRoundIsRecordedAsFailed() {
        UUID recent = order("CONFIRMED", "2 days");
        RealExecutionPurgeJob tooYoung =
                new RealExecutionPurgeJob(retentionPool, locks, metrics, appClock, "1 day", "1 day");

        RealExecutionPurgeJob.Round round = tooYoung.runOnce();

        assertThat(round.result()).isEqualTo("FAILED");
        assertThat(round.failureCause()).isPresent();
        assertThat(exists("real_order", "real_order_id", recent)).isTrue();
        assertThat(recorded(round.runId().orElseThrow())).isEqualTo("FAILED");
    }

    @Test
    void theRunLogIsCleanedAfter90Days() {
        UUID old = UUID.randomUUID();
        connect(RETENTION)
                .sql("insert into real_execution_purge_run (run_id, started_at, finished_at, cutoff, deleted_count,"
                        + " batches, result) values (:id, now() - interval '91 days', now(), now(), 0, 0,"
                        + " 'SUCCEEDED')")
                .param("id", old)
                .update();

        purge.runOnce();

        assertThat(connect(RETENTION)
                        .sql("select count(*) from real_execution_purge_run where run_id = :id")
                        .param("id", old)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
