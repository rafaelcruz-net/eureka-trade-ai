package com.memetrade.txsigner.testsupport;

import com.memetrade.txsigner.domain.PolicyChange;
import com.memetrade.txsigner.domain.SignerStore;
import com.memetrade.txsigner.domain.SigningRecord;
import com.memetrade.txsigner.domain.WalletPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link SignerStore} of the unit tests, with the same uniqueness rules as the database. */
public final class InMemorySignerStore implements SignerStore {

    private final Map<String, WalletPolicy> policies = new LinkedHashMap<>();
    private final Map<String, Instant> nonces = new LinkedHashMap<>();
    private final Map<UUID, PolicyChange> changes = new LinkedHashMap<>();
    private final Map<UUID, SigningRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized Optional<WalletPolicy> findPolicy(String botWalletAddress) {
        return Optional.ofNullable(policies.get(botWalletAddress));
    }

    @Override
    public synchronized Optional<WalletPolicy> findPolicyByCreationRequest(UUID creationRequestId) {
        return policies.values().stream()
                .filter(p -> p.creationRequestId().equals(creationRequestId))
                .findFirst();
    }

    @Override
    public synchronized int countPoliciesCreatedSince(Instant since) {
        return (int) policies.values().stream()
                .filter(p -> !p.restored() && !p.createdAt().isBefore(since))
                .count();
    }

    @Override
    public synchronized List<WalletPolicy> listPoliciesCreatedBefore(Instant before, int limit) {
        return policies.values().stream()
                .filter(p -> p.createdAt().isBefore(before))
                .sorted(Comparator.comparing(WalletPolicy::createdAt))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean isNonceUsed(String nonce) {
        return nonces.containsKey(nonce);
    }

    @Override
    public synchronized WriteResult insertPolicy(WalletPolicy policy, String nonce, Instant noncePurgeAfter) {
        if (findPolicyByCreationRequest(policy.creationRequestId()).isPresent()
                || policies.containsKey(policy.botWalletAddress())) {
            return WriteResult.DUPLICATE_REQUEST;
        }
        if (nonces.containsKey(nonce)) {
            return WriteResult.NONCE_USED;
        }
        nonces.put(nonce, noncePurgeAfter);
        policies.put(policy.botWalletAddress(), policy);
        return WriteResult.APPLIED;
    }

    @Override
    public synchronized WriteResult insertRestoredPolicy(WalletPolicy policy) {
        if (policies.containsKey(policy.botWalletAddress())) {
            return WriteResult.DUPLICATE_REQUEST;
        }
        policies.put(policy.botWalletAddress(), policy);
        return WriteResult.APPLIED;
    }

    @Override
    public synchronized WriteResult applyChange(
            PolicyChange change, WalletPolicy updated, String nonce, Instant noncePurgeAfter) {
        if (changes.containsKey(change.requestId())) {
            return WriteResult.DUPLICATE_REQUEST;
        }
        if (nonce != null && nonces.containsKey(nonce)) {
            return WriteResult.NONCE_USED;
        }
        if (nonce != null) {
            nonces.put(nonce, noncePurgeAfter);
        }
        changes.put(change.requestId(), change);
        policies.put(updated.botWalletAddress(), updated);
        return WriteResult.APPLIED;
    }

    @Override
    public synchronized void updatePolicy(WalletPolicy policy) {
        policies.put(policy.botWalletAddress(), policy);
    }

    private final Map<UUID, Instant[]> recoveryRuns = new LinkedHashMap<>();

    @Override
    public synchronized void startRecoveryRun(UUID runId, Instant startedAt) {
        recoveryRuns.put(runId, new Instant[] {startedAt, null});
    }

    @Override
    public synchronized void completeRecoveryRun(UUID runId, Instant completedAt, int restored, int blocked) {
        recoveryRuns.get(runId)[1] = completedAt;
    }

    public synchronized Instant recoveryCompletedAt(UUID runId) {
        return recoveryRuns.get(runId)[1];
    }

    /** Test hook: changes a stored policy directly (for example to simulate a restored database). */
    public synchronized void put(WalletPolicy policy) {
        policies.put(policy.botWalletAddress(), policy);
    }

    public synchronized void remove(String botWalletAddress) {
        policies.remove(botWalletAddress);
    }

    @Override
    public synchronized Optional<PolicyChange> findChange(UUID requestId) {
        return Optional.ofNullable(changes.get(requestId));
    }

    @Override
    public synchronized Optional<SigningRecord> findSigningRecord(UUID signingRequestId) {
        return Optional.ofNullable(records.get(signingRequestId));
    }

    @Override
    public synchronized boolean insertSigningRecord(SigningRecord record) {
        return records.putIfAbsent(record.signingRequestId(), record) == null;
    }

    @Override
    public synchronized List<SigningRecord> listSignedSince(Instant since, int limit) {
        return records.values().stream()
                .filter(r -> r.decision() == SigningRecord.Decision.SIGNED
                        && !r.decidedAt().isBefore(since))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized int purgeNonces(Instant now, int batchSize) {
        List<String> expired = new ArrayList<>();
        nonces.forEach((nonce, purgeAfter) -> {
            if (purgeAfter.isBefore(now) && expired.size() < batchSize) {
                expired.add(nonce);
            }
        });
        expired.forEach(nonces::remove);
        return expired.size();
    }

    public synchronized int recordCount() {
        return records.size();
    }

    public synchronized int nonceCount() {
        return nonces.size();
    }
}
