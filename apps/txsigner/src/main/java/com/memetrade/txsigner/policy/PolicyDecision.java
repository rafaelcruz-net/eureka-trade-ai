package com.memetrade.txsigner.policy;

import com.memetrade.txsigner.codec.SolanaTransaction;

/** Outcome of the content policy: approved with the decoded transaction, or refused with exactly one reason. */
public record PolicyDecision(boolean approved, PolicyRefusal refusal, SolanaTransaction transaction) {

    public static PolicyDecision approve(SolanaTransaction transaction) {
        return new PolicyDecision(true, null, transaction);
    }

    public static PolicyDecision refuse(PolicyRefusal refusal) {
        return new PolicyDecision(false, refusal, null);
    }
}
