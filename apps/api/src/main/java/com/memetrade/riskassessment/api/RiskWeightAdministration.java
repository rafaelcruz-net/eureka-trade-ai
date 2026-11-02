package com.memetrade.riskassessment.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Administration of the weight versions (F7), owned by U4 and used by the administrative screen of U8; U4 ships no
 * REST route for it. Every operation first requires an administrator through {@code AdminAccess} with the request of
 * the calling administrative route: any other role gets {@code 403 FORBIDDEN_ROLE} with exactly one
 * {@code REQUEST_FORBIDDEN} and nothing changes (BR4.2, NFR6.4).
 *
 * <p>Errors ({@link com.memetrade.shared.error.ApiException}): {@code 400 VALIDATION_FAILED} with the list of fields
 * (BR4.1, nothing written), {@code 404 NOT_FOUND} for an unknown version and {@code 409 WEIGHT_SET_NOT_EDITABLE} to
 * change an ACTIVE or RETIRED version or to activate a RETIRED one.
 */
public interface RiskWeightAdministration {

    /** Validates the draft and stores it as the next version, DRAFT, created by the administrator. */
    WeightSetView create(WeightSetDraft draft, HttpServletRequest request);

    /** Replaces the content of a DRAFT version; ACTIVE and RETIRED versions never change. */
    WeightSetView update(int version, WeightSetDraft draft, HttpServletRequest request);

    /**
     * Activates a DRAFT version: in one transaction the ACTIVE version becomes RETIRED, this one ACTIVE, and one
     * {@code RISK_WEIGHTS_ACTIVATED} event is published; after the commit every assessment is recalculated. Activating
     * the version that is already ACTIVE changes nothing and publishes nothing. Concurrent activations run one after
     * the other.
     */
    ActivationResult activate(int version, HttpServletRequest request);

    /** Every version, newest first. */
    List<WeightSetView> list(HttpServletRequest request);

    /**
     * @param changed false when the version was already ACTIVE (no change, no event)
     * @param retiredVersion the version that was ACTIVE before, when it changed
     */
    record ActivationResult(int activeVersion, java.util.Optional<Integer> retiredVersion, boolean changed) {}
}
