package com.memetrade.txsigner.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the TxSigner, over its OWN database (the api and the worker have no credential there). Every
 * method that changes a policy also records its nonce in the same transaction, so a nonce is used exactly once
 * (BR1.8) and a failure never leaves a used nonce without its change.
 */
public interface SignerStore {

    enum WriteResult {
        APPLIED,
        DUPLICATE_REQUEST,
        NONCE_USED
    }

    Optional<WalletPolicy> findPolicy(String botWalletAddress);

    Optional<WalletPolicy> findPolicyByCreationRequest(UUID creationRequestId);

    int countPoliciesCreatedSince(Instant since);

    List<WalletPolicy> listPoliciesCreatedBefore(Instant before, int limit);

    boolean isNonceUsed(String nonce);

    /** Inserts a new policy and its nonce; DUPLICATE_REQUEST when the creationRequestId already exists. */
    WriteResult insertPolicy(WalletPolicy policy, String nonce, Instant noncePurgeAfter);

    /** Inserts a policy rebuilt from the key tags by the recovery (no consent, no nonce). */
    WriteResult insertRestoredPolicy(WalletPolicy policy);

    /** Updates a policy, records the change and its nonce (null for a lowering) atomically. */
    WriteResult applyChange(PolicyChange change, WalletPolicy updated, String nonce, Instant noncePurgeAfter);

    /** Updates the mutable columns of a policy (limits, closed, blocked); the anchor never changes. */
    void updatePolicy(WalletPolicy policy);

    Optional<PolicyChange> findChange(UUID requestId);

    Optional<SigningRecord> findSigningRecord(UUID signingRequestId);

    /** {@code false} when a record with the same signingRequestId already exists (a concurrent repetition). */
    boolean insertSigningRecord(SigningRecord record);

    List<SigningRecord> listSignedSince(Instant since, int limit);

    /** Deletes the nonces whose purgeAfter passed; returns how many. */
    int purgeNonces(Instant now, int batchSize);

    /** Records the start of a run of the recovery mode (NFR4.13). */
    void startRecoveryRun(UUID runId, Instant startedAt);

    /** Writes {@code recoveryCompletedAt} of the run with its counts. */
    void completeRecoveryRun(UUID runId, Instant completedAt, int restored, int blocked);
}
