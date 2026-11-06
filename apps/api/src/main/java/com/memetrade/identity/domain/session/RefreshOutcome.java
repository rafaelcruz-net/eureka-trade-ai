package com.memetrade.identity.domain.session;

/** Result of a refresh; refusals still commit (revocation and the reuse event must persist). */
public sealed interface RefreshOutcome {

    record Renewed(OpenedSession session) implements RefreshOutcome {}

    record Refused(Reason reason) implements RefreshOutcome {}

    enum Reason {
        MISSING,
        UNKNOWN,
        REUSED,
        CLOSED,
        EXPIRED,
        INACTIVE_ACCOUNT,
        CONCURRENT_ROTATION,
        /** U8 (BR1.4): the route no longer admits the session (the wallet left the administrator list); revoked. */
        NOT_ALLOWED
    }
}
