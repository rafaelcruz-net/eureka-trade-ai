package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily schedule of the Identity purges (NFR7.1): login challenges at 03:20 UTC and sessions at 03:40 UTC, with the zone
 * declared explicitly, only in the api process. Active only where scheduling is enabled; integration tests call
 * {@link IdentityJobRunner} directly.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class IdentityJobSchedule {

    private final IdentityJobRunner runner;

    public IdentityJobSchedule(IdentityJobRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${memetrade.identity.purge.challenges-cron:0 20 3 * * *}", zone = "UTC")
    public void loginChallengePurge() {
        runner.runScheduled(IdentityPurge.LOGIN_CHALLENGE_PURGE);
    }

    @Scheduled(cron = "${memetrade.identity.purge.sessions-cron:0 40 3 * * *}", zone = "UTC")
    public void sessionPurge() {
        runner.runScheduled(IdentityPurge.SESSION_PURGE);
    }
}
