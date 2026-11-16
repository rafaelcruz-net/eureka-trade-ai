package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.AbstractRealOpsIT;
import com.memetrade.botwallet.application.RealKillSwitchService;
import com.memetrade.botwallet.domain.ReleaseState;
import com.memetrade.realexecution.api.RealExecutionPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 14 of the U5, written after the code (U5-NFR5.1, U5-NFR5.3 to U5-NFR5.5, U5-NFR5.13): the job {@code ops-gauges}
 * reads, against the injected clock, the releases, the BLOCKED age, the open divergences, the wallets DEFERRED for more than 2 hours, the kill switch
 * with its unsent orders, the restore phase and the pending commands, publishes them and counts its heartbeat. The
 * database is shared by the tests, so the assertions compare with a reading taken before.
 */
@Tag("u5-real-operations")
class RealOpsGaugesIT extends AbstractRealOpsIT {

    @Autowired
    private JdbcClient appJdbc;

    @Autowired
    private GlobalPauses pauses;

    @Autowired
    private RealExecutionPort execution;

    @Autowired
    private RealOpsMetrics metrics;

    @Autowired
    private RealOpsProperties properties;

    @Autowired
    private RealKillSwitchService killSwitch;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Clock appClock;

    private RealOpsGaugesJob job() {
        return new RealOpsGaugesJob(appJdbc, pauses, execution, metrics, properties, appClock);
    }

    private void run(OpsPerson person, String result, String age) {
        jdbc.sql("""
                        insert into real_reconciliation_run (run_id, tenant_id, bot_wallet_id, trigger, slot,
                          chain_lamports, ledger_lamports, result, started_at, finished_at)
                        values (gen_random_uuid(), :t, :w, 'SCHEDULE',
                          case when :result in ('CLEAN', 'DIVERGENT') then 1 end,
                          case when :result in ('CLEAN', 'DIVERGENT') then 1 end,
                          case when :result in ('CLEAN', 'DIVERGENT') then 1 end,
                          :result, cast(:now as timestamptz) - cast(:age as interval), cast(:now as timestamptz) - cast(:age as interval))""")
                .param("now", java.sql.Timestamp.from(appClock.instant()))
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("result", result)
                .param("age", age)
                .update();
    }

    @Test
    void aWalletDeferredForMoreThan2HoursCountsUntilItIsMeasuredAgain() throws Exception {
        OpsPerson person = activePerson();
        long before = job().read().deferredOver2h();
        run(person, "CLEAN", "5 hours");
        run(person, "DEFERRED", "3 hours");
        run(person, "UNAVAILABLE", "1 hour");

        long deferred = job().read().deferredOver2h();
        run(person, "CLEAN", "1 minute");
        long measured = job().read().deferredOver2h();

        assertThat(deferred).isEqualTo(before + 1);
        assertThat(measured).isEqualTo(before);
    }

    @Test
    void aWalletDeferredForLessThan2HoursDoesNotCount() throws Exception {
        OpsPerson person = activePerson();
        long before = job().read().deferredOver2h();
        run(person, "DEFERRED", "30 minutes");

        assertThat(job().read().deferredOver2h()).isEqualTo(before);
    }

    @Test
    void theReleasesByStateAndTheAgeOfTheOldestBlockedOne() throws Exception {
        OpsPerson person = activePerson();
        long blockedBefore = job().read().releases().get(ReleaseState.BLOCKED);
        UUID blocked = releaseOf(person.userId(), "LOCALNET", "BLOCKED", java.time.Duration.ofHours(27));

        RealOpsMetrics.GaugeValues values = job().read();

        assertThat(blocked).isNotNull();
        assertThat(values.releases().get(ReleaseState.BLOCKED)).isEqualTo(blockedBefore + 1);
        assertThat(values.blockedOldestSeconds()).isGreaterThanOrEqualTo(3 * 3600 - 60);
        assertThat(values.releases()).containsKeys(ReleaseState.values());
    }

    @Test
    void thePendingCommandsByKindAndTheOldestAge() throws Exception {
        OpsPerson person = activePerson();
        long before = job().read().pendingCommands().getOrDefault("LOWER_CAP", 0L);
        jdbc.sql("""
                        insert into real_pending_txsigner_command (command_id, bot_wallet_id, kind, payload, created_at)
                        values (gen_random_uuid(), :w, 'LOWER_CAP', '{"capLamports":"1"}'::jsonb,
                          cast(:now as timestamptz) - interval '20 minutes')""")
                .param("now", java.sql.Timestamp.from(appClock.instant()))
                .param("w", person.botWalletId())
                .update();

        RealOpsMetrics.GaugeValues values = job().read();

        assertThat(values.pendingCommands().get("LOWER_CAP")).isEqualTo(before + 1);
        assertThat(values.pendingOldestSeconds()).isGreaterThanOrEqualTo(1200 - 60);
    }

    @Test
    void theKillSwitchCountsItsUnsentOrdersOnlyWhileOn() throws Exception {
        OpsPerson person = activePerson();
        order(person, "QUOTED");
        RealOpsMetrics.GaugeValues off = job().read();

        killSwitch.activate(UUID.randomUUID(), "incidente");
        RealOpsMetrics.GaugeValues on = job().read();

        assertThat(off.killSwitchActive()).isFalse();
        assertThat(off.killSwitchUnsentOrders()).isZero();
        assertThat(on.killSwitchActive()).isTrue();
        assertThat(on.killSwitchUnsentOrders()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aRunPublishesTheGaugesAndCountsTheHeartbeat() throws Exception {
        double runs = counter();
        jdbc.sql("insert into real_restore_marker (marker_id, phase, opened_at, detected_at, unknown_signatures)"
                        + " values (gen_random_uuid(), 'CHECK_PENDING', now(), null, 0)")
                .update();

        job().run();

        assertThat(counter()).isEqualTo(runs + 1);
        assertThat(meters.get("real_restore_pause_active")
                        .tag("phase", "CHECK_PENDING")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meters.get("real_restore_pause_active")
                        .tag("phase", "RESTORE")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
        assertThat(meters.find("real_divergences_open").gauge()).isNotNull();
    }

    private double counter() {
        return meters.get("real_ops_job_runs")
                .tag("job", "ops-gauges")
                .counter()
                .count();
    }
}
