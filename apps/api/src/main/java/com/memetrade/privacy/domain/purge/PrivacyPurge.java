package com.memetrade.privacy.domain.purge;

import com.memetrade.audit.api.AuditActions;

/**
 * The daily purges of the Privacy module (u4-data-requests, BR4.15). The {@link #purgeId()} names the ShedLock lock,
 * the {@code privacy_job_run} row, the health component, the metric label and the retention declaration (J7); the
 * {@link #action()} is the audit event published at the end of each run.
 */
public enum PrivacyPurge {
    /** BR4.15: requests closed 12 months ago or more, with their verifications, at 04:00 UTC. */
    DATA_SUBJECT_REQUEST_PURGE("dataSubjectRequestPurge", AuditActions.DATA_REQUESTS_PURGED);

    private final String purgeId;
    private final String action;

    PrivacyPurge(String purgeId, String action) {
        this.purgeId = purgeId;
        this.action = action;
    }

    public String purgeId() {
        return purgeId;
    }

    public String action() {
        return action;
    }

    /** @throws IllegalArgumentException for a name that is not a purge of Privacy */
    public static PrivacyPurge fromPurgeId(String purgeId) {
        for (PrivacyPurge purge : values()) {
            if (purge.purgeId.equals(purgeId)) {
                return purge;
            }
        }
        throw new IllegalArgumentException("Unknown Privacy purge: " + purgeId);
    }
}
