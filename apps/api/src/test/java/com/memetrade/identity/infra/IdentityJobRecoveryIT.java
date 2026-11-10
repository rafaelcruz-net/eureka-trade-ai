package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import com.memetrade.shared.health.HealthStatuses;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;

/**
 * Passo 4 of u2-retention (tests written after the implementation; BR2.16, NFR7.2, NFR7.3, AC3.1.3): the startup
 * recovery and the health components of the Identity purges on the real api context.
 */
@Tag("u2-retention")
class IdentityJobRecoveryIT extends AbstractIdentityJobIT {

    @Autowired
    private IdentityJobRecovery recovery;

    @Autowired
    private PurgeRunStore runs;

    @Autowired
    private HealthEndpoint health;

    @Test
    void withoutARowBothPurgesRunAtStartupAndThenAreUp() {
        String nonce = challenge(clock.instant().minus(Duration.ofDays(2)));

        assertThat(recovery.recoverOverduePurges())
                .containsExactly(IdentityPurge.LOGIN_CHALLENGE_PURGE, IdentityPurge.SESSION_PURGE);

        assertThat(challengeExists(nonce)).isFalse();
        assertThat(lastResult(IdentityPurge.LOGIN_CHALLENGE_PURGE)).isEqualTo("SUCCEEDED");
        assertThat(lastResult(IdentityPurge.SESSION_PURGE)).isEqualTo("SUCCEEDED");
        assertThat(status("loginChallengePurge")).isEqualTo(Status.UP);
        assertThat(status("sessionPurge")).isEqualTo(Status.UP);
    }

    @Test
    void aRecentSuccessIsNotRunAgainAndAnOldOneIs() {
        recovery.recoverOverduePurges();
        releaseLocks();

        clock.advance(Duration.ofHours(23));
        assertThat(recovery.recoverOverduePurges()).isEmpty();

        clock.advance(Duration.ofHours(2));
        assertThat(recovery.recoverOverduePurges())
                .containsExactly(IdentityPurge.LOGIN_CHALLENGE_PURGE, IdentityPurge.SESSION_PURGE);
        assertThat(runs.find(IdentityPurge.SESSION_PURGE).orElseThrow().lastSuccessAt())
                .contains(clock.instant());
    }

    @Test
    void aRunHeldByAnotherInstanceIsSkippedWithoutError() {
        recovery.recoverOverduePurges();
        jdbc.sql("delete from identity_job_run").update();
        String nonce = challenge(clock.instant().minus(Duration.ofDays(2)));

        assertThat(recovery.recoverOverduePurges()).hasSize(2);

        assertThat(challengeExists(nonce))
                .as("the 5-minute lock of the first run still holds")
                .isTrue();
        assertThat(runs.find(IdentityPurge.LOGIN_CHALLENGE_PURGE)).isEmpty();
    }

    @Test
    void theHealthComponentsShowTheLastRunOfAnyInstanceFromTheDatabase() {
        jdbc.sql("""
                        insert into identity_job_run
                          (purge_id, last_run_at, last_result, last_success_at, last_cutoff, deleted)
                        values ('sessionPurge', :runAt, 'FAILED', :success, :cutoff, 0)
                        """)
                .param("runAt", java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(1))))
                .param("success", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(5))))
                .param("cutoff", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(31))))
                .update();
        clock.advance(Duration.ofHours(49));

        assertThat(status("sessionPurge")).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(runs.find(IdentityPurge.SESSION_PURGE).orElseThrow().lastResult())
                .isEqualTo(PurgeResult.FAILED);

        releaseLocks();
        runner.runLocked(IdentityPurge.SESSION_PURGE).orElseThrow();
        assertThat(status("sessionPurge")).isEqualTo(Status.UP);
    }

    private Status status(String name) {
        HealthComponent component = health.healthForPath(name);
        assertThat(component).as("health component %s", name).isNotNull();
        return component.getStatus();
    }
}
