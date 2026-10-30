package com.memetrade.identity.domain.login;

/** States of the login challenge state machine (functional-spec.md). CONSUMED and EXPIRED are final. */
public enum ChallengeState {
    ISSUED,
    CONSUMED,
    EXPIRED
}
