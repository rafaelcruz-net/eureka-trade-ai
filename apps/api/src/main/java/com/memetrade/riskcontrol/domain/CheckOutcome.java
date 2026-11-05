package com.memetrade.riskcontrol.domain;

import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The result of the checks of one evaluation (BR3.1 to BR3.3): the failed reasons in the fixed order with one detail
 * each, the pause triggers, and the two derived figures recorded with a rejection (MarketInputs.estimatedSlippagePct
 * and orderValue), {@link RiskFigure.Unknown} when they could not be computed.
 */
public record CheckOutcome(
        List<ReasonDetail> details,
        Set<PauseTrigger> pauseTriggers,
        RiskFigure estimatedSlippagePct,
        RiskFigure orderValue) {

    public CheckOutcome {
        Objects.requireNonNull(estimatedSlippagePct, "estimatedSlippagePct");
        Objects.requireNonNull(orderValue, "orderValue");
        details = List.copyOf(details);
        pauseTriggers = pauseTriggers.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(pauseTriggers));
        List<RejectionReason> reasons =
                details.stream().map(ReasonDetail::reason).toList();
        if (!reasons.equals(List.copyOf(reasons.isEmpty() ? List.of() : EnumSet.copyOf(reasons)))) {
            throw new IllegalArgumentException("One detail per reason, in the fixed order of BR3.1");
        }
    }

    /** No reason failed: the order is approved (the only path to the execution, NFR10.2). */
    public boolean approved() {
        return details.isEmpty();
    }

    public List<RejectionReason> reasons() {
        return details.stream().map(ReasonDetail::reason).toList();
    }

    /** The detail of {@code reason}; throws when the reason did not fail. */
    public ReasonDetail detail(RejectionReason reason) {
        return details.stream()
                .filter(d -> d.reason() == reason)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(reason + " did not fail"));
    }
}
