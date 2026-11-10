package com.memetrade.identity.domain.purge;

import com.memetrade.audit.api.AuditActions;

/**
 * The daily purges of the Identity module (u2-retention, BR2.5 to BR2.7). The {@link #purgeId()} names the ShedLock
 * lock, the {@code identity_job_run} row, the health component, the metric label and the retention declaration
 * (BR2.17); the {@link #action()} is the audit event published at the end of each run.
 */
public enum IdentityPurge {
    /** BR2.5: login challenges 24 hours after they expired, at 03:20 UTC. */
    LOGIN_CHALLENGE_PURGE("loginChallengePurge", AuditActions.LOGIN_CHALLENGES_PURGED),

    /** BR2.6: sessions and their used refresh digests 30 days after the end, at 03:40 UTC. */
    SESSION_PURGE("sessionPurge", AuditActions.SESSIONS_PURGED);

    private final String purgeId;
    private final String action;

    IdentityPurge(String purgeId, String action) {
        this.purgeId = purgeId;
        this.action = action;
    }

    public String purgeId() {
        return purgeId;
    }

    public String action() {
        return action;
    }

    /** @throws IllegalArgumentException for a name that is not a purge of Identity */
    public static IdentityPurge fromPurgeId(String purgeId) {
        for (IdentityPurge purge : values()) {
            if (purge.purgeId.equals(purgeId)) {
                return purge;
            }
        }
        throw new IllegalArgumentException("Unknown Identity purge: " + purgeId);
    }
}
