package com.memetrade.riskcontrol.real.infra;

import com.memetrade.shared.config.ProcessRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U3 (F3, F4, F5, infrastructure-specification.md "Compute"): the schedule of the jobs of the
 * REAL barrier, only in the worker. F3 every 5 minutes at the start of each 5-minute UTC slot (so the first round after
 * 00:00 UTC takes the photos and resumes the DAILY_LOSS pauses of the day before), F4 and F5 every minute. Each round
 * runs once across instances (ShedLock in {@link RealRiskJobs}).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RealRiskSchedule {

    private final RealRiskJobs jobs;

    public RealRiskSchedule(RealRiskJobs jobs) {
        this.jobs = jobs;
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void day() {
        jobs.runDay();
    }

    @Scheduled(fixedDelayString = "60s", initialDelayString = "20s")
    public void dailyLoss() {
        jobs.runDailyLoss();
    }

    @Scheduled(fixedDelayString = "60s", initialDelayString = "40s")
    public void reservationExpiry() {
        jobs.runReservationExpiry();
    }
}
