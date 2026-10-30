package com.memetrade.audit.domain;

/**
 * The two daily jobs of the AuditTrail (reliability-design.md). The name is the key of {@code audit_job_run}, the
 * ShedLock lock name and the {@code job} label of the job metrics.
 */
public enum AuditJob {
    /** Deletes trail events older than the retention cutoff with the retention login (F4, BR3.1, BR3.2). */
    AUDIT_RETENTION_PURGE,
    /** Deletes outbox rows delivered more than 7 days ago with the application login (F5, BR3.4). */
    AUDIT_OUTBOX_CLEANUP
}
