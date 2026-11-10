package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.InMemoryPurgeRunStore;
import com.memetrade.identity.domain.purge.PurgeHealthPolicy;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.shared.health.HealthStatuses;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Passo 2 of u2-retention, written before the code (BR2.8, BR2.16, AC3.1.3): the health of a purge is UP during the
 * first 48 hours after the start; DEGRADED once more than 48 hours passed both since the start and since the last
 * success; UP again after a success. The state comes from the run store, so every instance shows the same.
 */
@Tag("u2-retention")
class PurgeHealthIndicatorTest {

    private static final Instant STARTED = Instant.parse("2026-10-28T12:00:00Z");

    private final MutableClock clock = new MutableClock(STARTED);
    private final InMemoryPurgeRunStore runs = new InMemoryPurgeRunStore();
    private final PurgeHealthIndicator indicator = new PurgeHealthIndicator(
            IdentityPurge.SESSION_PURGE,
            runs,
            new PurgeHealthPolicy(Duration.ofHours(48), Duration.ofHours(24)),
            STARTED,
            clock);

    @Test
    void itIsUpDuringTheFirst48HoursAfterTheStartEvenWithoutAnyRun() {
        clock.advance(Duration.ofHours(47));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("purge", "sessionPurge").containsEntry("lastResult", "NEVER_RUN");
    }

    @Test
    void exactly48HoursAfterTheStartIsStillUp() {
        clock.advance(Duration.ofHours(48));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void itIsDegradedMoreThan48HoursAfterTheStartWithoutAnySuccess() {
        runs.record(run(STARTED.plus(Duration.ofHours(20)), PurgeResult.FAILED, null));
        clock.advance(Duration.ofHours(48).plusSeconds(1));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(health.getDetails()).containsEntry("lastResult", "FAILED");
    }

    @Test
    void itIsDegradedWhenTheLastSuccessIsOlderThan48Hours() {
        clock.set(STARTED.plus(Duration.ofDays(5)));
        Instant success = clock.instant().minus(Duration.ofHours(49));
        runs.record(run(success, PurgeResult.SUCCEEDED, success));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(health.getDetails()).containsEntry("lastSuccessAt", success.toString());
    }

    @Test
    void itIsUpAgainAfterASuccess() {
        clock.set(STARTED.plus(Duration.ofDays(5)));
        runs.record(run(STARTED, PurgeResult.SUCCEEDED, STARTED));
        assertThat(indicator.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);

        runs.record(run(clock.instant(), PurgeResult.SUCCEEDED, clock.instant()));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aFailureAfterARecentSuccessKeepsItUpWhileTheSuccessIsWithin48Hours() {
        clock.set(STARTED.plus(Duration.ofDays(5)));
        Instant success = clock.instant().minus(Duration.ofHours(30));
        runs.record(run(success, PurgeResult.SUCCEEDED, success));
        runs.record(run(clock.instant().minus(Duration.ofHours(6)), PurgeResult.FAILED, null));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("lastResult", "FAILED")
                .containsEntry("lastSuccessAt", success.toString())
                .containsEntry(
                        "lastRunAt", clock.instant().minus(Duration.ofHours(6)).toString());
    }

    @Test
    void anOldSuccessIsForgivenDuringThe48HoursAfterARestart() {
        Instant oldSuccess = STARTED.minus(Duration.ofDays(4));
        runs.record(run(oldSuccess, PurgeResult.SUCCEEDED, oldSuccess));
        clock.advance(Duration.ofHours(10));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void anUnavailableStoreMakesItDegradedNamingOnlyTheErrorClass() {
        runs.failWith(new DataAccessResourceFailureException("connection refused to secret-host"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(health.getDetails())
                .containsEntry("purge", "sessionPurge")
                .containsEntry("error", "DataAccessResourceFailureException");
        assertThat(health.getDetails().toString()).doesNotContain("secret-host");
    }

    @Test
    void thePolicyRecoversAPurgeWithoutRowWithoutSuccessOrWithASuccessOlderThan24Hours() {
        PurgeHealthPolicy policy = new PurgeHealthPolicy(Duration.ofHours(48), Duration.ofHours(24));
        Instant now = STARTED;

        assertThat(policy.shouldRunAtStartup(Optional.empty(), now)).isTrue();
        assertThat(policy.shouldRunAtStartup(Optional.of(run(now, PurgeResult.FAILED, null)), now))
                .isTrue();
        Instant old = now.minus(Duration.ofHours(25));
        assertThat(policy.shouldRunAtStartup(Optional.of(run(old, PurgeResult.SUCCEEDED, old)), now))
                .isTrue();
        Instant recent = now.minus(Duration.ofHours(23));
        assertThat(policy.shouldRunAtStartup(Optional.of(run(recent, PurgeResult.SUCCEEDED, recent)), now))
                .isFalse();
    }

    private static PurgeRun run(Instant at, PurgeResult result, Instant success) {
        return new PurgeRun(
                IdentityPurge.SESSION_PURGE,
                at,
                result,
                Optional.ofNullable(success),
                at.minus(Duration.ofDays(30)),
                0);
    }
}
