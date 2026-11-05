package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily schedule of the AuditTrail jobs (reliability-design.md): purge at 03:00 UTC and outbox cleanup at 03:30 UTC,
 * with the zone declared explicitly. Active only where scheduling is enabled (the API process); integration tests
 * call {@link AuditJobRunner} directly.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class AuditJobSchedule {

    private final AuditJobRunner runner;

    public AuditJobSchedule(AuditJobRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${memetrade.audit.jobs.retention-cron}", zone = "UTC")
    public void retentionPurge() {
        runner.runScheduled(AuditJob.AUDIT_RETENTION_PURGE);
    }

    @Scheduled(cron = "${memetrade.audit.jobs.outbox-cleanup-cron}", zone = "UTC")
    public void outboxCleanup() {
        runner.runScheduled(AuditJob.AUDIT_OUTBOX_CLEANUP);
    }
}
