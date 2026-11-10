package com.memetrade.privacy.domain.request;

/** The state of a signature verification (entities.md IdentityVerification.state, BR4.12, BR4.13). */
public enum VerificationState {
    PENDING,
    CONFIRMED,
    EXPIRED
}
