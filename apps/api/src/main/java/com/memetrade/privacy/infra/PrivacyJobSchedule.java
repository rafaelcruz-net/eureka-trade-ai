package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.shared.config.ProcessRole;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedule of the data subject requests (infrastructure-specification.md), only in the api process and with the zone
 * declared explicitly: the timers every 15 minutes (lock {@code dataRequestTimers}) and the purge of the closed requests
 * at 04:00 UTC (lock {@code dataSubjectRequestPurge}). Active only where scheduling is enabled; integration tests call
 * {@link DataRequestTimersJob} and {@link PrivacyJobRunner} directly.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class PrivacyJobSchedule {

    private final DataRequestTimersJob timers;
    private final PrivacyJobRunner runner;

    public PrivacyJobSchedule(DataRequestTimersJob timers, PrivacyJobRunner runner) {
        this.timers = timers;
        this.runner = runner;
    }

    @Scheduled(cron = "${memetrade.privacy.timers-cron:0 */15 * * * *}", zone = "UTC")
    public void dataRequestTimers() {
        timers.runOnce();
    }

    @Scheduled(cron = "${memetrade.privacy.purge-cron:0 0 4 * * *}", zone = "UTC")
    public void dataSubjectRequestPurge() {
        runner.runScheduled(PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE);
    }
}
