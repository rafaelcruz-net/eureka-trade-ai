package com.memetrade.riskcontrol.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Result of {@link OrderGate#evaluate} (C14 as changed by the U6 Functional Design: Q8 and Q9). */
public sealed interface GateResult permits GateResult.Approved, GateResult.Rejected {

    /** Every applicable check passed; the only way to {@code PaperExecution.execute}. */
    record Approved(CandidateOrder order) implements GateResult {

        public Approved {
            Objects.requireNonNull(order, "order");
        }
    }

    /**
     * The order is refused (BR3.1): {@code reasons} in the fixed order of {@link RejectionReason}, the first being the
     * main one shown on the screen; {@code pauseTriggers} is a set, empty when none (BR3.3). {@code rejectionId}
     * identifies the recorded {@code OrderRejection} read by C21.
     */
    record Rejected(
            UUID rejectionId, List<RejectionReason> reasons, long limitsVersion, Set<PauseTrigger> pauseTriggers)
            implements GateResult {

        public Rejected {
            Objects.requireNonNull(rejectionId, "rejectionId");
            Objects.requireNonNull(reasons, "reasons");
            Objects.requireNonNull(pauseTriggers, "pauseTriggers");
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("A rejection has at least one reason");
            }
            List<RejectionReason> ordered = new ArrayList<>(EnumSet.copyOf(reasons));
            if (!ordered.equals(reasons)) {
                throw new IllegalArgumentException("Reasons are unique and in the fixed order of BR3.1");
            }
            if (reasons.contains(RejectionReason.DUPLICATE) && reasons.size() > 1) {
                throw new IllegalArgumentException("DUPLICATE is always the only reason (BR2.8)");
            }
            if (limitsVersion < 1) {
                throw new IllegalArgumentException("limitsVersion must be at least 1");
            }
            reasons = List.copyOf(reasons);
            pauseTriggers =
                    pauseTriggers.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(pauseTriggers));
        }

        /** The main reason (BR3.1). */
        public RejectionReason mainReason() {
            return reasons.getFirst();
        }
    }
}
