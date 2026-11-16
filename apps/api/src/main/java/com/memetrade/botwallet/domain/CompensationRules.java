package com.memetrade.botwallet.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U5 (BR2.3, U5-NFR4.6): a compensation has exactly one item per divergent asset of the last
 * measure (SOL and each mint), each equal to the difference measured (network minus ledger), so the administrator never
 * posts an arbitrary value. Each item becomes one COMPENSATION entry whose idempotency key comes from the divergence, the
 * measure and the asset: a repeated request never appends twice.
 */
public final class CompensationRules {

    /** The stable code of the refusal (422). */
    public static final String MISMATCH = "COMPENSATION_MISMATCH";

    private CompensationRules() {}

    public static Optional<String> check(List<Compensation> items, long lamportsDiff, List<TokenDiff> tokenDiffs) {
        Map<String, Long> expected = new HashMap<>();
        if (lamportsDiff != 0) {
            expected.put(Compensation.SOL, lamportsDiff);
        }
        tokenDiffs.forEach(diff -> expected.put(diff.mint(), diff.diff()));
        Map<String, Long> given = new HashMap<>();
        for (Compensation item : items) {
            if (given.put(item.asset(), item.amount()) != null) {
                return Optional.of(MISMATCH);
            }
        }
        return expected.isEmpty() || !expected.equals(given) ? Optional.of(MISMATCH) : Optional.empty();
    }

    public static String idempotencyKey(UUID divergenceId, UUID measuredBy, String asset) {
        return "comp:" + divergenceId + ":" + measuredBy + ":" + asset;
    }
}
