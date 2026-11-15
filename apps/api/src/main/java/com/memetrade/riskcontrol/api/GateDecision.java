package com.memetrade.riskcontrol.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U3 (contract M5, entities.md GateDecision): the result of {@link RealRiskGate#evaluate}.
 * {@code evaluationId} is the row of {@code real_risk_evaluation}; a repeated evaluation of the same decision returns
 * the decision recorded the first time (BR6.1).
 */
public sealed interface GateDecision permits GateDecision.Approved, GateDecision.Rejected {

    UUID evaluationId();

    boolean approved();

    /**
     * @param approvalId the reservation of the barrier, only for an approved BUY; the U4 hands it over in the
     *     transaction of its {@code RESERVE} (BR6.2)
     */
    record Approved(UUID evaluationId, Optional<UUID> approvalId) implements GateDecision {
        public Approved {
            Objects.requireNonNull(evaluationId, "evaluationId");
            Objects.requireNonNull(approvalId, "approvalId");
        }

        @Override
        public boolean approved() {
            return true;
        }
    }

    /**
     * @param figures the numbers used by the failed check, in lamports, base units, seconds or basis points, of the
     *     evaluated wallet only (U3-NFR6.3); never a mint, an address or a person
     * @param detailMint the mint of the open position whose price is unknown or stale, only with
     *     {@link RealRejectionDetail#POSITION_PRICE_UNKNOWN}
     */
    record Rejected(
            UUID evaluationId,
            RealRejectionCode code,
            RealRejectionDetail detail,
            Map<String, Long> figures,
            Optional<String> detailMint)
            implements GateDecision {
        public Rejected {
            Objects.requireNonNull(evaluationId, "evaluationId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(detailMint, "detailMint");
            figures = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(figures));
        }

        @Override
        public boolean approved() {
            return false;
        }
    }
}
