package com.memetrade.bots.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U1 (BR2.1, BR2.2, ADR-005): the precedence of the REAL eligibility, a pure rule over the
 * sources it asks in order. The first reason that holds ends the evaluation, so a later source is never asked (and a
 * later source that would fail never fails a reason already decided, BR2.5):
 *
 * <ol>
 *   <li>the kill switch REAL is on: KILL_SWITCH;
 *   <li>the REAL of the environment is off: REAL_DISABLED;
 *   <li>the person has no ACTIVE bot wallet: WALLET_INACTIVE;
 *   <li>the release on the network of the environment is NONE, BLOCKED, REVOKED (or any state not known here, which
 *       fails closed): NOT_RELEASED;
 *   <li>the release is in QUARANTINE: QUARANTINE with its {@code effectiveAt}, even when that instant already passed
 *       (only the gates job turns the release ACTIVE);
 *   <li>otherwise available.
 * </ol>
 *
 * The same rule serves the read (C2) and the creation command (C1); the result of the screen is never reused (BR2.3).
 */
public final class RealCreationEligibility {

    private RealCreationEligibility() {}

    /** The state of the release of the person on the network of the environment. */
    public record Release(String state, Optional<Instant> effectiveAt) {
        public Release {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
        }
    }

    /** The sources of the rule, asked lazily and in order; each one may read the database. */
    public interface Sources {
        boolean killSwitchActive();

        boolean realEnabled();

        boolean walletActive();

        Release release();
    }

    public static RealEligibility evaluate(Sources sources) {
        if (sources.killSwitchActive()) {
            return RealEligibility.refused(RealIneligibilityReason.KILL_SWITCH, Optional.empty());
        }
        if (!sources.realEnabled()) {
            return RealEligibility.refused(RealIneligibilityReason.REAL_DISABLED, Optional.empty());
        }
        if (!sources.walletActive()) {
            return RealEligibility.refused(RealIneligibilityReason.WALLET_INACTIVE, Optional.empty());
        }
        Release release = sources.release();
        return switch (release.state()) {
            case "ACTIVE" -> RealEligibility.availableNow();
            case "QUARANTINE" -> RealEligibility.refused(RealIneligibilityReason.QUARANTINE, release.effectiveAt());
            default -> RealEligibility.refused(RealIneligibilityReason.NOT_RELEASED, Optional.empty());
        };
    }
}
