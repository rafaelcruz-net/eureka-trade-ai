package com.memetrade.txsigner.domain.kms;

/**
 * The KMS refused the call for its request quota (ThrottlingException, LimitExceededException): still a technical
 * failure (503, retried later, never a signature), counted apart as {@code THROTTLED} in
 * {@code txsigner_kms_errors_total} (NFR5.14, NFR5.19).
 */
public class KmsThrottledException extends KmsUnavailableException {

    public KmsThrottledException(String message, Throwable cause) {
        super(message, cause);
    }

    public KmsThrottledException(String message) {
        super(message);
    }
}
