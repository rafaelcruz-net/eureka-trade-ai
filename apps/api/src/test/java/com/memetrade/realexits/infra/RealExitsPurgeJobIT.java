package com.memetrade.realexits.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.infra.RiskRetentionDataSource;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 12 of the U7, written BEFORE the code (U7-NFR3.7, scalability-design.md): the purge of the REAL exits, in the
 * api, deletes through the retention pool the conditionals that ended more than 5 years (and a day) ago; a conditional
 * that is not final, or that ended less than 5 years ago, is never deleted: the age trigger of the migration 0022
 * refuses it even when the selection is wrong, and the round fails without deleting it.
 */
@Tag("u7-exits-and-fiscal")
class RealExitsPurgeJobIT extends AbstractPostgresIT {

    @Autowired
    private RealExitsPurgeJob purge;

    @Autowired
    private RiskRetentionDataSource retentionPool;

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private RealExitsMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Clock appClock;

    private UUID tenant;
    private UUID user;

    @BeforeEach
    void person() {
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        tenant = seeded.tenantId();
        user = seeded.userId();
    }

    /** A conditional of the person; ended {@code finalAge} ago (FILLED), or OPEN when null. */
    private UUID conditional(String finalAge) {
        UUID id = UUID.randomUUID();
        boolean ended = finalAge != null;
        jdbc.sql("""
                        insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,
                          position_mint, kind, stop_price_usd, amount_base_units, slippage_bps, state, adapter,
                          filled_side, idempotency_key, final_at, created_at, updated_at)
                        values (:id, :t, :u, gen_random_uuid(), :m, 'STOP', 0.1, 10, 200, :state, 'SIMULATED',
                          :side, :key, case when :ended then now() - cast(:age as interval) end,
                          now() - interval '6 years', now() - interval '6 years')""")
                .param("id", id)
                .param("t", tenant)
                .param("u", user)
                .param("m", SyntheticIds.mint())
                .param("state", ended ? "FILLED" : "OPEN")
                .param("side", ended ? "STOP" : null)
                .param("key", id.toString())
                .param("ended", ended)
                .param("age", ended ? finalAge : "0 seconds")
                .update();
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.sql("select count(*) from real_conditional_order where conditional_order_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Test
    void theConditionalsEndedMoreThanFiveYearsAgoAreDeleted() {
        UUID old = conditional("5 years 2 days");

        RealExitsPurgeJob.Round round = purge.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(exists(old)).isFalse();
    }

    @Test
    void aConditionalEndedLessThanFiveYearsAgoOrNotFinalIsKept() {
        UUID young = conditional("4 years 11 months");
        UUID open = conditional(null);

        purge.runOnce();

        assertThat(exists(young)).isTrue();
        assertThat(exists(open)).isTrue();
    }

    /** The age trigger refuses a wrong selection: the round fails and nothing recent is deleted. */
    @Test
    void aWrongSelectionFailsTheRoundAndTheTriggerKeepsTheRecentRow() {
        UUID young = conditional("2 days");
        RealExitsPurgeJob wrong = new RealExitsPurgeJob(retentionPool, locks, metrics, appClock, "1 day");

        RealExitsPurgeJob.Round round = wrong.runOnce();

        assertThat(round.result()).isEqualTo("FAILED");
        assertThat(exists(young)).isTrue();
    }

    @Test
    void anotherInstanceHoldingTheLockSkipsTheRound() throws Exception {
        LockConfiguration held = new LockConfiguration(
                appClock.instant(), RealExitsPurgeJob.LOCK_NAME, Duration.ofMinutes(5), Duration.ZERO);
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Thread other = new Thread(() -> locks.executeWithLock(
                (Runnable) () -> {
                    holding.countDown();
                    try {
                        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                },
                held));
        other.start();
        holding.await(10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            assertThat(purge.runOnce().ran()).isFalse();
        } finally {
            done.countDown();
            other.join(10_000);
        }
    }

    @Test
    void aSuccessfulRoundCountsTheHeartbeatOfThePurge() {
        double before = meters.counter("real_exits_job_runs", "job", "purge").count();

        purge.runOnce();

        assertThat(meters.counter("real_exits_job_runs", "job", "purge").count())
                .isGreaterThan(before);
    }

    @Test
    void theRetentionLoginIsTheOnlyOneThatDeletes() {
        UUID old = conditional("5 years 2 days");

        assertThat(com.memetrade.testsupport.LedgerRows.sqlState(
                        org.assertj.core.api.Assertions.catchThrowable(() -> connect(APP)
                                .sql("delete from real_conditional_order where conditional_order_id = :id")
                                .param("id", old)
                                .update())))
                .isEqualTo("42501");
        assertThat(exists(old)).isTrue();
    }
}
