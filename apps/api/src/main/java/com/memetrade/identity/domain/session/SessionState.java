package com.memetrade.identity.domain.session;

/** Session state machine (functional-spec.md): ACTIVE, then REVOKED or EXPIRED, both final. */
public enum SessionState {
    ACTIVE,
    REVOKED,
    EXPIRED
}
