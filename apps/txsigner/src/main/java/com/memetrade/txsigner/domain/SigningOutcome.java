package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.policy.PolicyRefusal;

/** Answer of a signing request: the signature and the signed transaction, or the refusal reason. */
public record SigningOutcome(boolean signed, String signature, String signedTransactionBase64, PolicyRefusal refusal) {

    public static SigningOutcome signed(String signature, String signedTransactionBase64) {
        return new SigningOutcome(true, signature, signedTransactionBase64, null);
    }

    public static SigningOutcome refused(PolicyRefusal refusal) {
        return new SigningOutcome(false, null, null, refusal);
    }
}
