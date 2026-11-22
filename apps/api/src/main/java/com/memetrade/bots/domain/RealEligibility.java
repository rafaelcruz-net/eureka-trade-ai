package com.memetrade.bots.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U1 (ENT-002, BR2.2): whether the person may create a REAL bot now and, when not, only the
 * strongest reason. Computed on every read (C2) and on every REAL creation (C1); never stored. It only allows the
 * creation and never authorizes trading (BR2.6).
 *
 * @param effectiveAt present if and only if the reason is QUARANTINE: the UTC instant the release starts to count
 */
public record RealEligibility(
        boolean available, Optional<RealIneligibilityReason> reason, Optional<Instant> effectiveAt) {

    /** BR2.2: the one action the screen offers, only for an inactive bot wallet. */
    public enum Action {
        ACTIVATE_BOT_WALLET
    }

    public RealEligibility {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (available == reason.isPresent()) {
            throw new IllegalArgumentException("A reason exists if and only if the REAL is not available");
        }
        if (effectiveAt.isPresent()
                && reason.filter(r -> r == RealIneligibilityReason.QUARANTINE).isEmpty()) {
            throw new IllegalArgumentException("effectiveAt exists only in QUARANTINE");
        }
    }

    public static RealEligibility availableNow() {
        return new RealEligibility(true, Optional.empty(), Optional.empty());
    }

    public static RealEligibility refused(RealIneligibilityReason reason, Optional<Instant> effectiveAt) {
        return new RealEligibility(
                false,
                Optional.of(reason),
                reason == RealIneligibilityReason.QUARANTINE ? effectiveAt : Optional.empty());
    }

    /** BR2.2: ACTIVATE_BOT_WALLET only with WALLET_INACTIVE. */
    public Optional<Action> action() {
        return reason.filter(r -> r == RealIneligibilityReason.WALLET_INACTIVE).map(r -> Action.ACTIVATE_BOT_WALLET);
    }
}
