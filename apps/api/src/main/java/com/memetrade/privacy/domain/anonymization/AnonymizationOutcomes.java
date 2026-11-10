package com.memetrade.privacy.domain.anonymization;

import java.util.UUID;

/**
 * The observation of one anonymization attempt (observability-design.md NFR8.1, NFR8.3, NFR8.4): the success is counted
 * and logged only after the commit, a failure only after the rollback, and the duration covers the whole transaction,
 * lock waits included. A refusal of BR5.1 (409, 422, 404) and a repetition (BR5.5) are neither a success nor a failure.
 */
public interface AnonymizationOutcomes {

    /** Starts observing an attempt bound to the current transaction. */
    Attempt start(UUID requestId);

    /** The closed reasons of a failure (the only label of the failure counter). */
    enum FailureReason {
        /** SQLSTATE 55P03: a lock was not obtained within {@code lock_timeout}. */
        LOCK_TIMEOUT,
        /** Any other exception after the checks; everything was rolled back. */
        ROLLED_BACK
    }

    /** One attempt; exactly one of the methods is called, or none when an unexpected failure escaped. */
    interface Attempt {

        /** The account was anonymized now; counted and logged after the commit. */
        void succeeded(AnonymizationResult result);

        /** A repetition on an account already ANONYMIZED: nothing to count. */
        void repeated();

        /** A refusal of the checks (BR5.1): nothing to count. */
        void refused();

        /** A failure after the checks; counted and logged with the class of {@code cause} after the rollback. */
        void failed(FailureReason reason, Throwable cause);
    }
}
