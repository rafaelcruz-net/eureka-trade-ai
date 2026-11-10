package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.purge.PurgeOutcome;
import com.memetrade.privacy.domain.purge.PurgeResult;
import com.memetrade.privacy.infra.PrivacyJobRecovery;
import com.memetrade.privacy.infra.PrivacyJobRunner;
import com.memetrade.shared.health.HealthStatuses;
import com.memetrade.testsupport.Concurrently;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Passo 6 of u4-data-requests (F4 step 3; BR4.15; AC5.8.1; NFR7.1, NFR3.5, NFR3.7): the purge of the closed requests on
 * the real database - a request closed 12 months ago leaves with its verification, a newer one and an open one stay;
 * the run records {@code privacy_job_run} with its event (counts only); two runs at once delete once; the recovery runs
 * an overdue purge; the health follows the last success; the batch reads the partial index of the closed requests.
 */
class DataSubjectRequestPurgeIT extends AbstractDataRequestIT {

    @Autowired
    private PrivacyJobRunner runner;

    @Autowired
    private PrivacyJobRecovery recovery;

    @Autowired
    @Qualifier("dataSubjectRequestPurgeHealthIndicator")
    private HealthIndicator health;

    @BeforeEach
    void releaseTheLock() {
        releaseLock();
    }

    private void releaseLock() {
        jdbc.sql("update shedlock set lock_until = now() - interval '1 second' where name = :n")
                .param("n", PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE.purgeId())
                .update();
    }

    private Instant cutoff() {
        return clock.instant().atZone(ZoneOffset.UTC).minusMonths(12).toInstant();
    }

    @Test
    void aRequestClosedTwelveMonthsAgoLeavesWithItsVerificationAndANewerOneStays() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String old = registered(admin, "ACCESS", guest.principal().userId());
        body(calls.startVerification(admin, old), 200);
        body(calls.transition(admin, old, "REFUSED", null, "Carteira perdida.", false), 200);
        jdbc.sql("update data_subject_request set closed_at = :at where request_id = :id")
                .param("at", Timestamp.from(cutoff()))
                .param("id", UUID.fromString(old))
                .update();
        String newer = created(guest.bearer(), "OTHER");
        closeDirectly(newer, cutoff().plusSeconds(1));
        String open = created(guest.bearer(), "CORRECTION");

        Optional<PurgeOutcome> outcome = runner.runLocked(PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE);

        assertThat(outcome).isPresent();
        assertThat(outcome.get().result()).isEqualTo(PurgeResult.SUCCEEDED);
        assertThat(outcome.get().deleted()).isGreaterThanOrEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(jdbc.sql("select count(*) from data_subject_verification where request_id = :id")
                        .param("id", UUID.fromString(old))
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(exists(newer)).isTrue();
        assertThat(exists(open)).isTrue();
    }

    @Test
    void theRunIsRecordedWithItsEventOfCountsOnly() throws Exception {
        WalletGuest guest = newWalletGuest();
        String id = created(guest.bearer(), "OTHER");
        closeDirectly(id, cutoff().minus(Duration.ofDays(1)));

        runner.runLocked(PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE);

        assertThat(jdbc.sql("select last_result from privacy_job_run where purge_id = 'dataSubjectRequestPurge'")
                        .query(String.class)
                        .single())
                .isEqualTo("SUCCEEDED");
        List<String> payloads = jdbc.sql("""
                        select payload::text from audit_outbox where payload->>'action' = 'DATA_REQUESTS_PURGED'
                        """).query(String.class).list();
        assertThat(payloads).isNotEmpty();
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.get("actorType").asText()).isEqualTo("SYSTEM");
            assertThat(event.get("details").fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("deleted", "batches", "verificationsDeleted", "result");
            assertThat(payload)
                    .doesNotContain(id)
                    .doesNotContain(guest.principal().tenantId().toString());
        }
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void twoRunsAtTheSameTimeDeleteOnce() throws Exception {
        WalletGuest guest = newWalletGuest();
        String id = created(guest.bearer(), "OTHER");
        closeDirectly(id, cutoff().minus(Duration.ofDays(2)));

        List<Concurrently.Outcome<Optional<PurgeOutcome>>> outcomes = Concurrently.run(
                () -> runner.runLocked(PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE),
                () -> runner.runLocked(PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failed()).isFalse());
        assertThat(outcomes)
                .filteredOn(outcome -> outcome.value().isPresent())
                .as("only one run takes the lock")
                .hasSize(1);
        assertThat(exists(id)).isFalse();
    }

    @Test
    void theRecoveryRunsAnOverduePurgeAndTheHealthIsDegradedAfterTwoDaysWithoutSuccess() throws Exception {
        jdbc.sql("""
                        insert into privacy_job_run (purge_id, last_run_at, last_result, last_success_at, last_cutoff, deleted)
                        values ('dataSubjectRequestPurge', :at, 'FAILED', :success, :at, 0)
                        on conflict (purge_id) do update set last_run_at = excluded.last_run_at,
                            last_result = excluded.last_result, last_success_at = excluded.last_success_at
                        """)
                .param("at", Timestamp.from(clock.instant().minus(Duration.ofDays(3))))
                .param("success", Timestamp.from(clock.instant().minus(Duration.ofDays(3))))
                .update();
        clock.advance(Duration.ofDays(3));
        Status before = health.health().getStatus();

        List<PrivacyPurge> attempted = recovery.recoverOverduePurges();

        assertThat(before).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(attempted).containsExactly(PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        releaseLock();
        assertThat(recovery.recoverOverduePurges())
                .as("a recent success is not run again")
                .isEmpty();
    }

    @Test
    void theBatchReadsThePartialIndexOfTheClosedRequests() {
        String plan = explain("""
                select request_id from data_subject_request
                where closed_at is not null and closed_at <= now()
                order by closed_at limit 1000
                """);

        assertThat(plan).contains("data_subject_request_closed_idx").doesNotContain("Seq Scan");
    }

    private boolean exists(String requestId) {
        return jdbc.sql("select count(*) from data_subject_request where request_id = :id")
                        .param("id", UUID.fromString(requestId))
                        .query(Long.class)
                        .single()
                > 0;
    }
}
