package com.memetrade.riskcontrol.real.infra;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 9 of the U3, written before the code (U3-NFR3.7, scalability-design.md, infrastructure-specification.md
 * "Registro do expurgo"): the daily purge of the REAL barrier deletes, through the retention pool and in batches of
 * 1.000, the evaluations, the CLOSED reservations and the photos older than 13 months; it never deletes a recent row
 * (the trigger refuses it even when the selection is wrong), records every round in {@code real_risk_purge_run}, cleans
 * that log after 90 days and moves the heartbeat of the job {@code purge}.
 */
class RealRiskPurgeJobIT extends AbstractPostgresIT {

    @Autowired
    private RealRiskPurgeJob purge;

    @Autowired
    private RiskRetentionDataSource retentionPool;

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private RealRiskMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Clock appClock;

    private JdbcClient app;
    private UUID tenant;
    private UUID user;

    @BeforeEach
    void seed() {
        app = connect(APP);
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        tenant = person.tenantId();
        user = person.userId();
    }

    private UUID evaluation(String age) {
        UUID id = UUID.randomUUID();
        app.sql(
                        "insert into real_risk_evaluation (evaluation_id, tenant_id, user_id, bot_id, decision_id, origin, side,"
                                + " chain, mint, input_lamports, outcome, evaluated_at, evaluated_day) values (:id, :tenant,"
                                + " :user, :bot, :decision, 'BOT', 'BUY', 'solana', :mint, 1000, 'APPROVED',"
                                + " now() - cast(:age as interval),"
                                + " cast((now() - cast(:age as interval)) at time zone 'UTC' as date))")
                .param("id", id)
                .param("tenant", tenant)
                .param("user", user)
                .param("bot", UUID.randomUUID())
                .param("decision", UUID.randomUUID())
                .param("mint", SyntheticIds.mint())
                .param("age", age)
                .update();
        return id;
    }

    private UUID reservation(String state, String age) {
        UUID id = UUID.randomUUID();
        app.sql(
                        "insert into real_risk_reservation (approval_id, tenant_id, user_id, bot_wallet_id, bot_id, decision_id,"
                                + " mint, input_lamports, state, created_at, expires_at, closed_at) values (:id, :tenant, :user,"
                                + " :wallet, :bot, :decision, :mint, 1000, :state, now() - cast(:age as interval),"
                                + " now() - cast(:age as interval) + interval '5 minutes',"
                                + " case when :state = 'OPEN' then null else now() - cast(:age as interval) end)")
                .param("id", id)
                .param("tenant", tenant)
                .param("user", user)
                .param("wallet", UUID.randomUUID())
                .param("bot", UUID.randomUUID())
                .param("decision", UUID.randomUUID())
                .param("mint", SyntheticIds.mint())
                .param("state", state)
                .param("age", age)
                .update();
        return id;
    }

    private UUID day(String age) {
        UUID wallet = UUID.randomUUID();
        app.sql("insert into real_risk_day (tenant_id, bot_wallet_id, day, start_value_lamports, start_cap_lamports,"
                        + " taken_at, taken_by) values (:tenant, :wallet, cast((now() - cast(:age as interval))"
                        + " at time zone 'UTC' as date), 10, 10, now() - cast(:age as interval), 'DAY_JOB')")
                .param("tenant", tenant)
                .param("wallet", wallet)
                .param("age", age)
                .update();
        return wallet;
    }

    private boolean exists(String table, String column, UUID id) {
        return jdbc.sql("select exists (select 1 from " + table + " where " + column + " = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    @Test
    void theRoundDeletesOnlyRowsOlderThan13MonthsAndKeepsOpenReservations() {
        UUID oldEvaluation = evaluation("14 months");
        UUID recentEvaluation = evaluation("12 months");
        UUID oldClosed = reservation("EXPIRED", "14 months");
        UUID oldOpen = reservation("OPEN", "14 months");
        UUID recentClosed = reservation("HANDED_OVER", "1 day");
        UUID oldDay = day("14 months");
        UUID recentDay = day("1 day");

        RealRiskPurgeJob.Round round = purge.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(round.deleted()).isGreaterThanOrEqualTo(3);
        assertThat(exists("real_risk_evaluation", "evaluation_id", oldEvaluation))
                .isFalse();
        assertThat(exists("real_risk_evaluation", "evaluation_id", recentEvaluation))
                .isTrue();
        assertThat(exists("real_risk_reservation", "approval_id", oldClosed)).isFalse();
        assertThat(exists("real_risk_reservation", "approval_id", oldOpen)).isTrue();
        assertThat(exists("real_risk_reservation", "approval_id", recentClosed)).isTrue();
        assertThat(exists("real_risk_day", "bot_wallet_id", oldDay)).isFalse();
        assertThat(exists("real_risk_day", "bot_wallet_id", recentDay)).isTrue();
    }

    @Test
    void aSecondRoundDeletesNothingMore() {
        evaluation("14 months");
        purge.runOnce();

        RealRiskPurgeJob.Round again = purge.runOnce();

        assertThat(again.result()).isEqualTo("SUCCEEDED");
        assertThat(again.deleted()).isZero();
    }

    @Test
    void theRowsGoInBatchesOf1000() {
        app.sql("""
                        insert into real_risk_evaluation (evaluation_id, tenant_id, user_id, bot_id, decision_id, origin,
                          side, chain, mint, input_lamports, outcome, evaluated_at, evaluated_day)
                        select gen_random_uuid(), :tenant, :user, gen_random_uuid(), gen_random_uuid(), 'BOT', 'BUY',
                          'solana', :mint, 1000, 'APPROVED', now() - interval '14 months',
                          cast((now() - interval '14 months') at time zone 'UTC' as date)
                        from generate_series(1, 1001)""")
                .param("tenant", tenant)
                .param("user", user)
                .param("mint", SyntheticIds.mint())
                .update();

        RealRiskPurgeJob.Round round = purge.runOnce();

        assertThat(round.deleted()).isGreaterThanOrEqualTo(1001);
        assertThat(round.batches()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void everyRoundIsRecordedAndMovesTheHeartbeatOfThePurge() {
        double before = meters.counter("real_risk_job_runs", "job", "purge").count();

        RealRiskPurgeJob.Round round = purge.runOnce();

        assertThat(connect(RETENTION)
                        .sql("select result from real_risk_purge_run where run_id = :id")
                        .param("id", round.runId().orElseThrow())
                        .query(String.class)
                        .single())
                .isEqualTo("SUCCEEDED");
        assertThat(meters.counter("real_risk_job_runs", "job", "purge").count()).isEqualTo(before + 1);
    }

    /** U3-NFR3.7: even a selection that reaches a recent row deletes nothing: the trigger refuses it. */
    @Test
    void aWrongSelectionNeverDeletesARecentRowAndTheRoundIsRecordedAsFailed() {
        UUID recent = evaluation("2 days");
        RealRiskPurgeJob tooYoung = new RealRiskPurgeJob(retentionPool, locks, metrics, appClock, "1 day");

        RealRiskPurgeJob.Round round = tooYoung.runOnce();

        assertThat(round.result()).isEqualTo("FAILED");
        assertThat(round.failureCause()).isPresent();
        assertThat(exists("real_risk_evaluation", "evaluation_id", recent)).isTrue();
        assertThat(connect(RETENTION)
                        .sql("select result from real_risk_purge_run where run_id = :id")
                        .param("id", round.runId().orElseThrow())
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
    }

    @Test
    void theRunLogIsCleanedAfter90Days() {
        UUID old = UUID.randomUUID();
        connect(RETENTION)
                .sql("insert into real_risk_purge_run (run_id, started_at, finished_at, cutoff, deleted_count,"
                        + " batches, result) values (:id, now() - interval '91 days', now(), now(), 0, 0,"
                        + " 'SUCCEEDED')")
                .param("id", old)
                .update();

        purge.runOnce();

        assertThat(connect(RETENTION)
                        .sql("select count(*) from real_risk_purge_run where run_id = :id")
                        .param("id", old)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
