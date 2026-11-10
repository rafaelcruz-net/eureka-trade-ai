package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.privacy.domain.request.DataRequestTimers;
import com.memetrade.privacy.infra.DataRequestCountsRefresher;
import com.memetrade.privacy.infra.DataRequestMetrics;
import com.memetrade.privacy.infra.DataRequestTimersJob;
import com.memetrade.shared.health.HealthStatuses;
import com.memetrade.testsupport.Concurrently;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Passo 6 of u4-data-requests (F4; BR4.13, BR4.14; NFR3.4, NFR3.7, NFR7.2, NFR8.1, NFR8.2): the job of 15 minutes on the
 * real database - an expired verification refuses its request with "identidade não confirmada" by the system, an
 * overdue request is notified once, running twice or concurrently duplicates nothing, the gauges and the health
 * {@code dataRequests} follow the cached counts, and the conditional updates read through their indexes.
 */
class DataRequestTimersIT extends AbstractDataRequestIT {

    @Autowired
    private DataRequestTimersJob timers;

    @Autowired
    private DataRequestCountsRefresher refresher;

    @Autowired
    private DataRequestMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    @Qualifier("dataRequestsHealthIndicator")
    private HealthIndicator health;

    @BeforeEach
    void releaseTheLock() {
        jdbc.sql("update shedlock set lock_until = now() - interval '1 second' where name = :n")
                .param("n", DataRequestTimersJob.LOCK_NAME)
                .update();
        closeEveryOpenRequest();
    }

    /** The health and the gauges are global: start each test from no open request of earlier tests. */
    private void closeEveryOpenRequest() {
        jdbc.sql("""
                        update data_subject_verification set state = 'EXPIRED' where state = 'PENDING'
                        """).update();
        jdbc.sql("""
                        update data_subject_request set status = 'REFUSED', refusal_reason = 'Fechado pelo teste.',
                            closed_at = now(), access_summary = null
                        where status in ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS')
                        """).update();
    }

    private void runAgain() {
        jdbc.sql("update shedlock set lock_until = now() - interval '1 second' where name = :n")
                .param("n", DataRequestTimersJob.LOCK_NAME)
                .update();
    }

    @Test
    void anExpiredVerificationRefusesItsRequestByTheSystem() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String id = registered(admin, "ACCESS", guest.principal().userId());
        body(calls.startVerification(admin, id), 200);
        clock.advance(Duration.ofHours(24));

        Optional<DataRequestTimers.Outcome> outcome = timers.runOnce();

        assertThat(outcome).isPresent();
        assertThat(outcome.get().refused()).isGreaterThanOrEqualTo(1);
        assertThat(status(id)).isEqualTo("REFUSED");
        assertThat(jdbc.sql("select refusal_reason from data_subject_request where request_id = :id")
                        .param("id", UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo(DataRequestTimers.IDENTITY_NOT_CONFIRMED);
        assertThat(jdbc.sql("""
                                select payload->>'actorType' from audit_outbox
                                where payload->>'action' = 'DATA_REQUEST_TRANSITIONED'
                                  and payload->'details'->>'requestId' = :id
                                """).param("id", id).query(String.class).single()).isEqualTo("SYSTEM");
    }

    @Test
    void aVerificationStillValidIsNotTouched() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String id = registered(admin, "DELETION", guest.principal().userId());
        body(calls.startVerification(admin, id), 200);
        clock.advance(Duration.ofHours(23));

        timers.runOnce();

        assertThat(status(id)).isEqualTo("AWAITING_VERIFICATION");
    }

    @Test
    void anOverdueRequestIsNotifiedOnceEvenWhenTheJobRunsTwice() throws Exception {
        WalletGuest guest = newWalletGuest();
        String id = created(guest.bearer(), "OTHER");
        clock.advance(Duration.ofDays(15));

        timers.runOnce();
        runAgain();
        clock.advance(Duration.ofMinutes(15));
        timers.runOnce();

        assertThat(events(AuditActions.DATA_REQUEST_OVERDUE, id)).isEqualTo(1);
        assertThat(jdbc.sql("select overdue_notified_at is not null from data_subject_request where request_id = :id")
                        .param("id", UUID.fromString(id))
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void twoConcurrentPassesPublishEachNoticeOnce() throws Exception {
        WalletGuest first = newWalletGuest();
        WalletGuest second = newWalletGuest();
        String a = created(first.bearer(), "OTHER");
        String b = created(second.bearer(), "OTHER");
        clock.advance(Duration.ofDays(16));
        DataRequestTimers domain = this.domainTimers;

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(
                () -> inTransaction(() -> domain.notifyBatch(clock.instant(), 500)),
                () -> inTransaction(() -> domain.notifyBatch(clock.instant(), 500)));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failed()).isFalse());
        assertThat(events(AuditActions.DATA_REQUEST_OVERDUE, a)).isEqualTo(1);
        assertThat(events(AuditActions.DATA_REQUEST_OVERDUE, b)).isEqualTo(1);
    }

    @Test
    void theGaugesAndTheHealthFollowTheOverdueRequestsAndComeBackWhenTheyClose() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String id = created(guest.bearer(), "ACCESS");
        clock.advance(Duration.ofDays(16));

        assertThat(refresher.refresh()).isTrue();
        Status degraded = health.health().getStatus();
        double overdue = meters.get("memetrade.data.requests.overdue").gauge().value();
        double open = meters.get("memetrade.data.requests.open")
                .tag("type", "ACCESS")
                .gauge()
                .value();
        body(calls.transition(admin, id, "IN_PROGRESS", null, null, false), 200);
        body(calls.transition(admin, id, "DONE", "Feito.", null, false), 200);
        refresher.refresh();

        assertThat(degraded).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(overdue).isEqualTo(1.0);
        assertThat(open).isEqualTo(1.0);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(metrics.overdue()).isZero();
        assertThat(health.health().getDetails())
                .containsKeys("overdue", "dueSoon")
                .doesNotContainKey("requestId");
    }

    @Test
    void aRequestDueWithinThreeDaysIsCountedAsDueSoon() throws Exception {
        WalletGuest guest = newWalletGuest();
        created(guest.bearer(), "OTHER");
        clock.advance(Duration.ofDays(13));

        refresher.refresh();

        assertThat(meters.get("memetrade.data.requests.due.soon").gauge().value())
                .isEqualTo(1.0);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void theCreatedCounterCountsByTypeAndOriginOnly() throws Exception {
        WalletGuest guest = newWalletGuest();
        double before = meters.get("memetrade.data.requests.created")
                .tag("type", "CORRECTION")
                .tag("origin", "ACCOUNT")
                .counter()
                .count();

        created(guest.bearer(), "CORRECTION");

        assertThat(meters.get("memetrade.data.requests.created")
                        .tag("type", "CORRECTION")
                        .tag("origin", "ACCOUNT")
                        .counter()
                        .count())
                .isEqualTo(before + 1);
        meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("memetrade.data.requests"))
                .forEach(meter -> assertThat(meter.getId().getTags())
                        .allMatch(tag ->
                                tag.getKey().equals("type") || tag.getKey().equals("origin")));
    }

    @Test
    void theConditionalUpdatesReadThroughTheirIndexes() {
        String overdue = explain("""
                select request_id from data_subject_request
                where status in ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS')
                  and due_at <= now() and overdue_notified_at is null
                order by due_at limit 500
                """);
        String expired = explain("""
                select request_id from data_subject_verification
                where state = 'PENDING' and expires_at <= now()
                order by expires_at limit 500
                """);

        assertThat(overdue).contains("data_subject_request_status_due_idx").doesNotContain("Seq Scan");
        assertThat(expired).contains("data_subject_verification_pending_idx").doesNotContain("Seq Scan");
    }

    @Autowired
    private DataRequestTimers domainTimers;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    private int inTransaction(java.util.function.IntSupplier batch) {
        Integer rows = new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> batch.getAsInt());
        return rows == null ? 0 : rows;
    }
}
