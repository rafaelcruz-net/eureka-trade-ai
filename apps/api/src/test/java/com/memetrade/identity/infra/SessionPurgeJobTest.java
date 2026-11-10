package com.memetrade.identity.infra;

import static com.memetrade.identity.infra.PurgeFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeOutcome;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import com.memetrade.testsupport.TestAccounts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u2-retention, written before the code (BR2.6, BR2.7, AC3.1.2): a session leaves 30 days after its end,
 * which is {@code revokedAt} when it was revoked and otherwise the earlier of its two expirations; a session whose end
 * is in the future never leaves, whatever its recorded state; its used refresh digests leave first, in the same batch;
 * guest and administrator sessions alike. Real PostgreSQL with the migrations of the application and an injectable
 * clock.
 */
@Tag("u2-retention")
class SessionPurgeJobTest {

    private static final PurgeFixtures DB = PurgeFixtures.connect();
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();
    private TestAccounts.Seeded owner;

    @BeforeEach
    void emptyTables() {
        DB.clearSessions();
        DB.clearRuns();
        owner = DB.person();
    }

    @Test
    void aRevokedSessionLeaves30DaysAfterRevokedAtAndNotBefore() {
        Instant expirations = NOW.plus(Duration.ofHours(2));
        UUID revokedLongAgo = DB.session(
                owner,
                "BETA_USER",
                "REVOKED",
                NOW.minus(Duration.ofDays(31)),
                NOW.minus(Duration.ofDays(31)),
                NOW.minus(Duration.ofDays(31)));
        UUID revokedOnTheLimit = DB.session(
                owner,
                "BETA_USER",
                "REVOKED",
                NOW.minus(Duration.ofDays(30)),
                NOW.minus(Duration.ofDays(30)),
                NOW.minus(THIRTY_DAYS));
        UUID revokedRecently =
                DB.session(owner, "BETA_USER", "REVOKED", expirations, expirations, NOW.minus(Duration.ofDays(29)));

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(outcome.result()).isEqualTo(PurgeResult.SUCCEEDED);
        assertThat(DB.sessionExists(revokedLongAgo)).isFalse();
        assertThat(DB.sessionExists(revokedOnTheLimit)).isFalse();
        assertThat(DB.sessionExists(revokedRecently)).isTrue();
    }

    @Test
    void theRevocationIsTheEndEvenWhenTheExpirationsAreOlder() {
        Instant old = NOW.minus(Duration.ofDays(40));
        UUID revokedLate = DB.session(owner, "BETA_USER", "REVOKED", old, old, NOW.minus(Duration.ofDays(29)));

        run(IdentityPurgeProperties.defaults());

        assertThat(DB.sessionExists(revokedLate)).isTrue();
    }

    @Test
    void aSessionNeverRevokedLeavesByTheEarlierOfItsTwoExpirations() {
        UUID refreshExpiredLongAgo = DB.session(
                owner, "BETA_USER", "ACTIVE", NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(5)), null);
        UUID bothRecent = DB.session(
                owner, "BETA_USER", "ACTIVE", NOW.minus(Duration.ofDays(29)), NOW.minus(Duration.ofDays(29)), null);
        UUID absoluteOnTheLimit = DB.endedSession(owner, NOW.minus(THIRTY_DAYS));
        UUID oneMillisecondInside =
                DB.endedSession(owner, NOW.minus(THIRTY_DAYS).plusMillis(1));

        run(IdentityPurgeProperties.defaults());

        assertThat(DB.sessionExists(refreshExpiredLongAgo)).isFalse();
        assertThat(DB.sessionExists(bothRecent)).isTrue();
        assertThat(DB.sessionExists(absoluteOnTheLimit)).isFalse();
        assertThat(DB.sessionExists(oneMillisecondInside)).isTrue();
    }

    @Test
    void aSessionWhoseEndIsInTheFutureNeverLeavesWhateverItsRecordedState() {
        Instant future = NOW.plus(Duration.ofHours(6));
        UUID revokedWithoutInstant = DB.session(owner, "BETA_USER", "REVOKED", future, future, null);
        UUID expiredWithoutInstant = DB.session(owner, "BETA_USER", "EXPIRED", future, future, null);
        UUID active = DB.session(owner, "BETA_USER", "ACTIVE", future, future.plus(Duration.ofHours(4)), null);

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(outcome.deleted()).isZero();
        assertThat(DB.sessionExists(revokedWithoutInstant)).isTrue();
        assertThat(DB.sessionExists(expiredWithoutInstant)).isTrue();
        assertThat(DB.sessionExists(active)).isTrue();
    }

    @Test
    void theUsedDigestsLeaveFirstInTheSameBatchAndThoseOfAKeptSessionStay() {
        UUID ended = DB.endedSession(owner, NOW.minus(Duration.ofDays(45)));
        DB.digests(ended, 3);
        UUID kept = DB.endedSession(owner, NOW.minus(Duration.ofDays(2)));
        DB.digests(kept, 2);

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(DB.sessionExists(ended)).isFalse();
        assertThat(DB.digestsOf(ended)).isZero();
        assertThat(DB.digestsOf(kept)).isEqualTo(2);
        assertThat(outcome.deleted()).isEqualTo(1);
        assertThat(outcome.related()).isEqualTo(3);
        assertThat(outcome.batches()).isEqualTo(1);
    }

    @Test
    void guestAndAdministratorSessionsAreBothPurged() {
        Instant old = NOW.minus(Duration.ofDays(35));
        UUID guest = DB.session(owner, "BETA_USER", "EXPIRED", old, old, old);
        UUID administrator = DB.session(owner, "ADMIN", "REVOKED", old, old, old);

        run(IdentityPurgeProperties.defaults());

        assertThat(DB.sessionExists(guest)).isFalse();
        assertThat(DB.sessionExists(administrator)).isFalse();
    }

    @Test
    void theCutoffIsTheInjectableClockMinus30Days() {
        clock.set(Instant.parse("2026-11-03T03:40:00Z"));

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(outcome.cutoff()).isEqualTo(Instant.parse("2026-10-04T03:40:00Z"));
    }

    @Test
    void itDeletesInBatchesUntilABatchComesBackShortWithTheDigestsOfEachBatch() {
        for (int i = 0; i < 5; i++) {
            UUID ended = DB.endedSession(owner, NOW.minus(Duration.ofDays(31 + i)));
            DB.digests(ended, 1);
        }

        PurgeOutcome outcome = run(PurgeFixtures.withBatchSize(2));

        assertThat(outcome.deleted()).isEqualTo(5);
        assertThat(outcome.related()).isEqualTo(5);
        assertThat(outcome.batches()).isEqualTo(3);
    }

    @Test
    void theRunPublishesOneSessionsPurgedEventWithBothCountsAndNoTenant() {
        UUID ended = DB.endedSession(owner, NOW.minus(Duration.ofDays(40)));
        DB.digests(ended, 2);

        run(IdentityPurgeProperties.defaults());

        List<AuditEventCommand> events = audit.withAction("SESSIONS_PURGED");
        assertThat(events).hasSize(1);
        AuditEventCommand event = events.getFirst();
        assertThat(event.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(event.tenantId()).isEmpty();
        assertThat(event.details())
                .containsEntry("deleted", "1")
                .containsEntry("digestsDeleted", "2")
                .containsEntry("batches", "1");
        assertThat(event.toString())
                .doesNotContain(owner.userId().toString(), owner.tenantId().toString());
    }

    private PurgeOutcome run(IdentityPurgeProperties settings) {
        IdentityJobRunner runner = DB.runner(clock, audit, settings, List.of(new SessionPurgeJob(DB.jdbc, settings)));
        return runner.runLocked(IdentityPurge.SESSION_PURGE).orElseThrow();
    }
}
