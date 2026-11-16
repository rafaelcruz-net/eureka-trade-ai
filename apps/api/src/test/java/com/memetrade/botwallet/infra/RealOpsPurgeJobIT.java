package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.AbstractRealOpsIT;
import com.memetrade.riskcontrol.infra.RiskRetentionDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 13 of the U5, written before the code (U5-NFR3.9, reliability-design.md, nfr-design review R-09): the daily purge
 * of the operations of the REAL deletes, through the retention pool, the reconciliations older than 13 months that no
 * divergence references ({@code NOT EXISTS}, so the {@code ON DELETE RESTRICT} is never hit), the confirmed commands to
 * the TxSigner older than 13 months, the divergences resolved more than 5 years ago (before the reconciliations, so their
 * runs go in the same round) and the releases revoked more than 5 years ago. A recent row is never deleted: the age
 * trigger refuses it even when the selection is wrong, and the round fails without deleting it.
 */
@Tag("u5-real-operations")
class RealOpsPurgeJobIT extends AbstractRealOpsIT {

    @Autowired
    private RealOpsPurgeJob purge;

    @Autowired
    private RiskRetentionDataSource retentionPool;

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private RealOpsMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Clock appClock;

    private UUID run(OpsPerson person, String result, String age) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into real_reconciliation_run (run_id, tenant_id, bot_wallet_id, trigger, slot,
                          chain_lamports, ledger_lamports, result, started_at, finished_at)
                        values (:id, :t, :w, 'SCHEDULE', 1, 1000, 1000, :result, now() - cast(:age as interval),
                          now() - cast(:age as interval))""")
                .param("id", id)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("result", result)
                .param("age", age)
                .update();
        return id;
    }

    /** A divergence of the wallet; resolved {@code resolvedAge} ago, or OPEN when null. */
    private UUID divergence(OpsPerson person, UUID run, UUID lastRun, String resolvedAge) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into real_divergence (divergence_id, run_id, last_run_id, tenant_id, bot_wallet_id,
                          lamports_diff, state, opened_at, updated_at, resolved_at)
                        values (:id, :run, :last, :t, :w, 10, case when cast(:age as text) is null then 'OPEN'
                          else 'RESOLVED' end, now() - interval '6 years', now() - interval '6 years',
                          now() - cast(:age as interval))""")
                .param("id", id)
                .param("run", run)
                .param("last", lastRun)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("age", resolvedAge)
                .update();
        return id;
    }

    /** A LOWER_CAP command created long ago; confirmed {@code confirmedAge} ago, or pending when null. */
    private UUID command(OpsPerson person, String confirmedAge) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into real_pending_txsigner_command (command_id, bot_wallet_id, kind, payload, created_at,
                          confirmed_at)
                        values (:id, :w, 'LOWER_CAP', '{"capLamports":"1000"}'::jsonb, now() - interval '2 years',
                          now() - cast(:age as interval))""")
                .param("id", id)
                .param("w", person.botWalletId())
                .param("age", confirmedAge)
                .update();
        return id;
    }

    /** A release requested 7 years ago; revoked {@code revokedAge} ago, or still ACTIVE when null. */
    private UUID release(OpsPerson person, String revokedAge) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          state, revoked_at)
                        values (:id, :u, 'DEVNET', :u, now() - interval '7 years', now() - interval '7 years'
                          + interval '24 hours', case when cast(:age as text) is null then 'ACTIVE' else 'REVOKED' end,
                          now() - cast(:age as interval))""")
                .param("id", id)
                .param("u", person.userId())
                .param("age", revokedAge)
                .update();
        return id;
    }

    private boolean exists(String table, String key, UUID id) {
        return jdbc.sql("select count(*) from " + table + " where " + key + " = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Test
    void theReconciliationsOlderThan13MonthsGoUnlessADivergenceReferencesThem() throws Exception {
        OpsPerson person = activePerson();
        UUID old = run(person, "CLEAN", "14 months");
        UUID oldDeferred = run(person, "DEFERRED", "14 months");
        UUID recent = run(person, "CLEAN", "12 months");
        UUID referenced = run(person, "DIVERGENT", "14 months");
        UUID referencedLast = run(person, "DIVERGENT", "14 months");
        divergence(person, referenced, referencedLast, null);

        RealOpsPurgeJob.Round round = purge.runOnce();

        assertThat(round.result()).isEqualTo(RealOpsPurgeJob.SUCCEEDED);
        assertThat(exists("real_reconciliation_run", "run_id", old)).isFalse();
        assertThat(exists("real_reconciliation_run", "run_id", oldDeferred)).isFalse();
        assertThat(exists("real_reconciliation_run", "run_id", recent)).isTrue();
        assertThat(exists("real_reconciliation_run", "run_id", referenced)).isTrue();
        assertThat(exists("real_reconciliation_run", "run_id", referencedLast)).isTrue();
    }

    @Test
    void aDivergenceResolvedMoreThan5YearsAgoGoesWithItsReconciliationsInTheSameRound() throws Exception {
        OpsPerson person = activePerson();
        UUID oldRun = run(person, "DIVERGENT", "6 years");
        UUID oldResolved = divergence(person, oldRun, oldRun, "5 years 2 days");
        OpsPerson other = activePerson();
        UUID keptRun = run(other, "DIVERGENT", "6 years");
        UUID recentResolved = divergence(other, keptRun, keptRun, "4 years");

        purge.runOnce();

        assertThat(exists("real_divergence", "divergence_id", oldResolved)).isFalse();
        assertThat(exists("real_reconciliation_run", "run_id", oldRun)).isFalse();
        assertThat(exists("real_divergence", "divergence_id", recentResolved)).isTrue();
        assertThat(exists("real_reconciliation_run", "run_id", keptRun)).isTrue();
    }

    @Test
    void onlyTheCommandsConfirmedMoreThan13MonthsAgoGo() throws Exception {
        OpsPerson person = activePerson();
        UUID old = command(person, "14 months");
        UUID recent = command(person, "12 months");
        UUID pending = command(person, null);

        purge.runOnce();

        assertThat(exists("real_pending_txsigner_command", "command_id", old)).isFalse();
        assertThat(exists("real_pending_txsigner_command", "command_id", recent))
                .isTrue();
        assertThat(exists("real_pending_txsigner_command", "command_id", pending))
                .isTrue();
    }

    @Test
    void onlyTheReleasesRevokedMoreThan5YearsAgoGo() throws Exception {
        OpsPerson person = activePerson();
        UUID old = release(person, "5 years 2 days");
        UUID recent = release(person, "4 years");
        OpsPerson other = activePerson();
        UUID active = release(other, null);

        purge.runOnce();

        assertThat(exists("real_release", "release_id", old)).isFalse();
        assertThat(exists("real_release", "release_id", recent)).isTrue();
        assertThat(exists("real_release", "release_id", active)).isTrue();
    }

    /** U5-NFR3.9: a wrong selection fails the round on the age trigger and never deletes a recent row. */
    @Test
    void aWrongSelectionFailsTheRoundWithoutDeletingARecentRow() throws Exception {
        OpsPerson person = activePerson();
        UUID recent = run(person, "CLEAN", "2 days");
        RealOpsPurgeJob wrong = new RealOpsPurgeJob(retentionPool, locks, metrics, appClock, "1 day", "1 day");

        RealOpsPurgeJob.Round round = wrong.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(round.result()).isEqualTo(RealOpsPurgeJob.FAILED);
        assertThat(round.failureCause()).isPresent();
        assertThat(exists("real_reconciliation_run", "run_id", recent)).isTrue();
    }

    @Test
    void aSucceededRoundCountsTheJobPurge() throws Exception {
        double before = count();

        RealOpsPurgeJob.Round round = purge.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(count()).isEqualTo(before + 1);
    }

    private double count() {
        var counter = meters.find("real_ops_job_runs").tag("job", "purge").counter();
        return counter == null ? 0 : counter.count();
    }
}
