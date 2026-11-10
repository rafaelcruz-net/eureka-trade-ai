package com.memetrade.privacy.domain.anonymization;

/**
 * NFR5.4: the anonymization waits for each lock at most the {@code lock_timeout} of the bots commands
 * ({@code memetrade.bots.lock-timeout}, 2 s), set for the current transaction only, before the first lock.
 */
public interface AnonymizationTimeouts {

    void applyLockTimeout();
}
