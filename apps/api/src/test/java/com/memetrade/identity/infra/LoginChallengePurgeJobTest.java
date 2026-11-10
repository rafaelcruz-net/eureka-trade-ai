package com.memetrade.identity.infra;

import static com.memetrade.identity.infra.PurgeFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeOutcome;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u2-retention, written before the code (BR2.5, BR2.7, AC3.1.1): a login challenge leaves 24 hours after it
 * expired, whatever its state; a more recent one stays; the limit of exactly 24 hours leaves; the purge deletes in
 * batches of 1000 until a batch comes back short. Real PostgreSQL with the migrations of the application and an
 * injectable clock.
 */
@Tag("u2-retention")
class LoginChallengePurgeJobTest {

    private static final PurgeFixtures DB = PurgeFixtures.connect();

    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();

    @BeforeEach
    void emptyTables() {
        DB.clearChallenges();
        DB.clearRuns();
    }

    @Test
    void aChallengeThatExpiredMoreThan24HoursAgoLeavesInAnyState() {
        Instant old = NOW.minus(Duration.ofHours(25));
        String issued = DB.challenge("ISSUED", old);
        String consumed = DB.challenge("CONSUMED", old);
        String expired = DB.challenge("EXPIRED", old);

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(outcome.result()).isEqualTo(PurgeResult.SUCCEEDED);
        assertThat(outcome.deleted()).isEqualTo(3);
        assertThat(DB.challengeExists(issued)).isFalse();
        assertThat(DB.challengeExists(consumed)).isFalse();
        assertThat(DB.challengeExists(expired)).isFalse();
    }

    @Test
    void aMoreRecentChallengeStaysWhateverItsState() {
        String recentExpired = DB.challenge("EXPIRED", NOW.minus(Duration.ofHours(23)));
        String recentConsumed = DB.challenge("CONSUMED", NOW.minus(Duration.ofHours(1)));
        String stillValid = DB.challenge("ISSUED", NOW.plus(Duration.ofMinutes(4)));

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(outcome.deleted()).isZero();
        assertThat(DB.challengeExists(recentExpired)).isTrue();
        assertThat(DB.challengeExists(recentConsumed)).isTrue();
        assertThat(DB.challengeExists(stillValid)).isTrue();
    }

    @Test
    void exactly24HoursAfterTheExpirationLeavesAndOneMillisecondLessStays() {
        String onTheLimit = DB.challenge("EXPIRED", NOW.minus(Duration.ofHours(24)));
        String justInside =
                DB.challenge("EXPIRED", NOW.minus(Duration.ofHours(24)).plusMillis(1));

        run(IdentityPurgeProperties.defaults());

        assertThat(DB.challengeExists(onTheLimit)).isFalse();
        assertThat(DB.challengeExists(justInside)).isTrue();
    }

    @Test
    void theCutoffIsTheInjectableClockMinus24Hours() {
        clock.set(Instant.parse("2026-11-03T03:20:00Z"));

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(outcome.cutoff()).isEqualTo(Instant.parse("2026-11-02T03:20:00Z"));
    }

    @Test
    void itDeletesInBatchesOf1000UntilABatchComesBackShort() {
        DB.challenges(2500, NOW.minus(Duration.ofDays(3)));

        PurgeOutcome outcome = run(IdentityPurgeProperties.defaults());

        assertThat(IdentityPurgeProperties.defaults().batchSize()).isEqualTo(1000);
        assertThat(outcome.deleted()).isEqualTo(2500);
        assertThat(outcome.batches()).isEqualTo(3);
        assertThat(DB.challengeCount()).isZero();
    }

    @Test
    void anExactMultipleOfTheBatchNeedsOneMoreEmptyBatchToStop() {
        DB.challenges(4, NOW.minus(Duration.ofDays(3)));

        PurgeOutcome outcome = run(PurgeFixtures.withBatchSize(2));

        assertThat(outcome.deleted()).isEqualTo(4);
        assertThat(outcome.batches()).isEqualTo(3);
    }

    @Test
    void theRunPublishesOneLoginChallengesPurgedEventBySystemWithTheCountOnly() {
        DB.challenge("ISSUED", NOW.minus(Duration.ofDays(2)));
        DB.challenge("CONSUMED", NOW.minus(Duration.ofDays(2)));

        run(IdentityPurgeProperties.defaults());

        List<AuditEventCommand> events = audit.withAction("LOGIN_CHALLENGES_PURGED");
        assertThat(events).hasSize(1);
        AuditEventCommand event = events.getFirst();
        assertThat(event.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(event.tenantId()).isEmpty();
        assertThat(event.details()).containsEntry("deleted", "2").containsEntry("batches", "1");
        assertThat(event.details().keySet())
                .allMatch(key -> List.of("deleted", "batches", "result").contains(key));
    }

    @Test
    void theRunIsRecordedAsSucceededWithItsCutoffAndCount() {
        DB.challenge("EXPIRED", NOW.minus(Duration.ofDays(2)));

        run(IdentityPurgeProperties.defaults());

        PurgeRun recorded = new JdbcIdentityJobRunStore(DB.jdbc)
                .find(IdentityPurge.LOGIN_CHALLENGE_PURGE)
                .orElseThrow();
        assertThat(recorded.lastResult()).isEqualTo(PurgeResult.SUCCEEDED);
        assertThat(recorded.lastRunAt()).isEqualTo(NOW);
        assertThat(recorded.lastSuccessAt()).contains(NOW);
        assertThat(recorded.lastCutoff()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(recorded.deleted()).isEqualTo(1);
    }

    private PurgeOutcome run(IdentityPurgeProperties settings) {
        IdentityJobRunner runner =
                DB.runner(clock, audit, settings, List.of(new LoginChallengePurgeJob(DB.jdbc, settings)));
        return runner.runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE).orElseThrow();
    }
}
