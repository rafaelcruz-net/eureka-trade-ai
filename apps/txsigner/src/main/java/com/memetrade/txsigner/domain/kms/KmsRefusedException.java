package com.memetrade.txsigner.domain.kms;

/**
 * The KMS refused the call (access denied, invalid key state, a tag change after creation, a creation without one of
 * the required tags). It is never retried and never turns into a signature.
 */
public class KmsRefusedException extends RuntimeException {

    public KmsRefusedException(String message, Throwable cause) {
        super(message, cause);
    }

    public KmsRefusedException(String message) {
        super(message);
    }
}
