package com.memetrade.privacy.domain;

import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The conformance of the retention catalog (BR2.10, BR2.12, AC1.4.1, AC1.4.2): every row has who applies the period it
 * states. A divergence names the row, the period the catalog declares and the period the code applies.
 *
 * <ul>
 *   <li>PURGE: the purge exists (a J7 declaration with that id) with the same term and the same anchor;
 *   <li>GUARD: the period is "while the account exists" (enforced already by {@link RetentionRule});
 *   <li>PLANNED: the row is in the pending list for the same Unit, and its purge does not exist yet;
 *   <li>a pending entry names a PLANNED row of the catalog; each purge declares itself once.
 * </ul>
 */
public final class RetentionConformance {

    /** One row that does not hold. */
    public record Divergence(String ruleId, String declared, String applied) {
        @Override
        public String toString() {
            return "Retention row " + ruleId + ": the catalog declares " + declared + ", the code applies " + applied;
        }
    }

    private RetentionConformance() {}

    public static List<Divergence> check(
            List<RetentionRule> rules,
            List<? extends RetentionEnforcerDeclaration> declarations,
            List<PendingRetention.Entry> pending) {
        List<Divergence> divergences = new ArrayList<>();
        Map<String, RetentionEnforcerDeclaration> byPurge = new HashMap<>();
        for (RetentionEnforcerDeclaration declaration : declarations) {
            if (byPurge.putIfAbsent(declaration.purgeId(), declaration) != null) {
                divergences.add(new Divergence(
                        declaration.purgeId(), "one declaration per purge", "more than one declaration"));
            }
        }
        for (RetentionRule rule : rules) {
            checkRule(rule, byPurge, pending).ifPresent(divergences::add);
        }
        for (PendingRetention.Entry entry : pending) {
            boolean plannedRow = rules.stream()
                    .anyMatch(rule ->
                            rule.id().equals(entry.ruleId()) && rule.enforcer().kind() == Enforcer.Kind.PLANNED);
            if (!plannedRow) {
                divergences.add(new Divergence(entry.ruleId(), "no pending row", "pending for " + entry.plannedUnit()));
            }
        }
        return List.copyOf(divergences);
    }

    private static Optional<Divergence> checkRule(
            RetentionRule rule,
            Map<String, RetentionEnforcerDeclaration> byPurge,
            List<PendingRetention.Entry> pending) {
        return switch (rule.enforcer().kind()) {
            case PURGE -> checkPurge(rule, byPurge);
            case GUARD ->
                rule.period().kind() == RetentionPeriod.Kind.WHILE_ACCOUNT_EXISTS
                        ? Optional.empty()
                        : Optional.of(new Divergence(rule.id(), rule.period().describe(), "a guard of the account"));
            case PLANNED -> checkPlanned(rule, byPurge, pending);
        };
    }

    private static Optional<Divergence> checkPurge(
            RetentionRule rule, Map<String, RetentionEnforcerDeclaration> byPurge) {
        String purgeId = rule.enforcer().purgeId().orElseThrow();
        RetentionEnforcerDeclaration declaration = byPurge.get(purgeId);
        String declared = rule.period().describe();
        if (declaration == null) {
            return Optional.of(new Divergence(rule.id(), declared, "nothing: no declaration of the purge " + purgeId));
        }
        String applied = declaration.retention() + " from " + declaration.anchor() + " (" + purgeId + ")";
        boolean same = rule.period().kind() == RetentionPeriod.Kind.FIXED
                && rule.period().term().orElseThrow().equals(declaration.retention())
                && rule.period().anchor().orElseThrow() == declaration.anchor();
        return same ? Optional.empty() : Optional.of(new Divergence(rule.id(), declared, applied));
    }

    private static Optional<Divergence> checkPlanned(
            RetentionRule rule,
            Map<String, RetentionEnforcerDeclaration> byPurge,
            List<PendingRetention.Entry> pending) {
        String unit = rule.enforcer().plannedUnit().orElseThrow();
        Optional<PendingRetention.Entry> entry = pending.stream()
                .filter(candidate -> candidate.ruleId().equals(rule.id()))
                .findFirst();
        if (entry.isEmpty() || !entry.get().plannedUnit().equals(unit)) {
            return Optional.of(new Divergence(
                    rule.id(),
                    rule.period().describe() + " by " + unit,
                    "nothing: the row is not in the pending list"));
        }
        RetentionEnforcerDeclaration existing = byPurge.get(entry.get().expectedPurgeId());
        if (existing != null) {
            return Optional.of(new Divergence(
                    rule.id(),
                    "PLANNED for " + unit,
                    existing.retention() + " from " + existing.anchor() + " (" + existing.purgeId()
                            + " already exists)"));
        }
        return Optional.empty();
    }
}
