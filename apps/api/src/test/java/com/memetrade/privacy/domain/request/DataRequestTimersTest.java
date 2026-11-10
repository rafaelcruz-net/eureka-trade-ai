package com.memetrade.privacy.domain.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 3 of u4-data-requests, written BEFORE the code: the job of 15 minutes (rules.md BR4.13, BR4.14; AC5.6.5,
 * AC5.7.1; reliability-design.md NFR7.2).
 *
 * <ul>
 *   <li>a PENDING verification past its 24 hours expires and its request is REFUSED by the system with the reason
 *       "identidade não confirmada"; one still valid stays;
 *   <li>an open request past its due date publishes ONE DATA_REQUEST_OVERDUE; a closed one or one still in time none;
 *   <li>running twice duplicates nothing; the rows are taken in batches until a batch comes back short.
 * </ul>
 */
@Tag("u4-data-requests")
class DataRequestTimersTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:30:00Z");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000e001");

    private final MutableClock clock = new MutableClock(NOW);
    private final RequestFakes.Store store = new RequestFakes.Store();
    private final RequestFakes.Events events = new RequestFakes.Events();

    private DataRequestTimers timers(int batchSize) {
        return new DataRequestTimers(store, events, clock, batchSize);
    }

    private DataSubjectRequest request(DataRequestStatus status, Instant receivedAt) {
        DataSubjectRequest request = DataSubjectRequest.fromAccount(
                UUID.randomUUID(),
                TENANT,
                UUID.randomUUID(),
                DataRequestType.OTHER,
                "Texto sintético.",
                receivedAt,
                Duration.ofDays(15));
        DataSubjectRequest moved =
                switch (status) {
                    case RECEIVED -> request;
                    case IN_PROGRESS -> request.inProgress(false);
                    case AWAITING_VERIFICATION -> request.awaitingVerification();
                    case DONE -> request.inProgress(false).done(Optional.of("Feito."), Optional.empty(), receivedAt);
                    case REFUSED -> request.inProgress(false).refused("Motivo.", receivedAt);
                };
        store.requests.put(moved.requestId(), moved);
        return moved;
    }

    private DataSubjectRequest awaiting(Instant expiresAt) {
        DataSubjectRequest request = request(DataRequestStatus.AWAITING_VERIFICATION, NOW.minus(Duration.ofDays(1)));
        store.verifications.put(
                request.requestId(),
                new IdentityVerification(
                        request.requestId(),
                        "mensagem",
                        expiresAt.minus(Duration.ofHours(24)),
                        expiresAt,
                        VerificationState.PENDING,
                        Optional.empty()));
        return request;
    }

    @Test
    void anExpiredVerificationRefusesItsRequestWithIdentityNotConfirmed() {
        DataSubjectRequest expired = awaiting(NOW.minus(Duration.ofMinutes(1)));

        DataRequestTimers.Outcome outcome = timers(500).run();

        DataSubjectRequest refused = store.requests.get(expired.requestId());
        assertThat(refused.status()).isEqualTo(DataRequestStatus.REFUSED);
        assertThat(refused.refusalReason()).contains("identidade não confirmada");
        assertThat(refused.closedAt()).contains(NOW);
        assertThat(store.verifications.get(expired.requestId()).state()).isEqualTo(VerificationState.EXPIRED);
        assertThat(outcome.refused()).isEqualTo(1);
        assertThat(events.published).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("TRANSITIONED");
            assertThat(event.from()).contains(DataRequestStatus.AWAITING_VERIFICATION);
            assertThat(event.to()).isEqualTo(DataRequestStatus.REFUSED);
            assertThat(event.actor()).isEqualTo("SYSTEM");
        });
        assertThat(DataRequestTimers.IDENTITY_NOT_CONFIRMED).isEqualTo("identidade não confirmada");
    }

    @Test
    void aVerificationExpiringExactlyNowIsExpiredAndOneStillValidStays() {
        DataSubjectRequest atTheLimit = awaiting(NOW);
        DataSubjectRequest valid = awaiting(NOW.plus(Duration.ofSeconds(1)));

        timers(500).run();

        assertThat(store.requests.get(atTheLimit.requestId()).status()).isEqualTo(DataRequestStatus.REFUSED);
        assertThat(store.requests.get(valid.requestId()).status()).isEqualTo(DataRequestStatus.AWAITING_VERIFICATION);
        assertThat(store.verifications.get(valid.requestId()).state()).isEqualTo(VerificationState.PENDING);
    }

    @Test
    void anOverdueOpenRequestPublishesOneNoticeAndRecordsIt() {
        DataSubjectRequest overdue = request(DataRequestStatus.IN_PROGRESS, NOW.minus(Duration.ofDays(16)));

        DataRequestTimers.Outcome outcome = timers(500).run();

        assertThat(outcome.notified()).isEqualTo(1);
        assertThat(events.published).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("OVERDUE");
            assertThat(event.requestId()).isEqualTo(overdue.requestId());
        });
        assertThat(store.requests.get(overdue.requestId()).overdueNotifiedAt()).contains(NOW);
    }

    @Test
    void eachOpenStateIsNotifiedWhenOverdue() {
        request(DataRequestStatus.RECEIVED, NOW.minus(Duration.ofDays(20)));
        request(DataRequestStatus.IN_PROGRESS, NOW.minus(Duration.ofDays(20)));
        DataSubjectRequest waiting = awaiting(NOW.plus(Duration.ofHours(1)));
        store.requests.put(
                waiting.requestId(),
                DataSubjectRequest.fromAccount(
                                waiting.requestId(),
                                TENANT,
                                waiting.userId().orElseThrow(),
                                DataRequestType.OTHER,
                                "Texto.",
                                NOW.minus(Duration.ofDays(20)),
                                Duration.ofDays(15))
                        .awaitingVerification());

        assertThat(timers(500).run().notified()).isEqualTo(3);
    }

    @Test
    void aRequestStillInTimeOrAlreadyClosedIsNeverNotified() {
        request(DataRequestStatus.RECEIVED, NOW.minus(Duration.ofDays(14)));
        request(DataRequestStatus.DONE, NOW.minus(Duration.ofDays(40)));
        request(DataRequestStatus.REFUSED, NOW.minus(Duration.ofDays(40)));

        assertThat(timers(500).run().notified()).isZero();
        assertThat(events.published).isEmpty();
    }

    @Test
    void aRequestDueExactlyNowIsOverdue() {
        request(DataRequestStatus.RECEIVED, NOW.minus(Duration.ofDays(15)));

        assertThat(timers(500).run().notified()).isEqualTo(1);
    }

    @Test
    void runningTwiceDuplicatesNothing() {
        awaiting(NOW.minus(Duration.ofHours(1)));
        request(DataRequestStatus.RECEIVED, NOW.minus(Duration.ofDays(30)));

        timers(500).run();
        clock.advance(Duration.ofMinutes(15));
        DataRequestTimers.Outcome second = timers(500).run();

        assertThat(second.refused()).isZero();
        assertThat(second.notified()).isZero();
        assertThat(events.kinds()).containsExactlyInAnyOrder("TRANSITIONED", "OVERDUE");
    }

    @Test
    void theRowsAreTakenInBatchesUntilOneComesBackShort() {
        for (int i = 0; i < 5; i++) {
            request(DataRequestStatus.RECEIVED, NOW.minus(Duration.ofDays(16 + i)));
        }
        for (int i = 0; i < 3; i++) {
            awaiting(NOW.minus(Duration.ofMinutes(1 + i)));
        }

        DataRequestTimers.Outcome outcome = timers(2).run();

        assertThat(outcome.refused()).isEqualTo(3);
        // the 3 refused requests are closed before the overdue pass, so only the 5 open ones are notified
        assertThat(outcome.notified()).isEqualTo(5);
        assertThat(events.published).hasSize(8);
    }

    @Test
    void anInvalidBatchSizeIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> timers(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
