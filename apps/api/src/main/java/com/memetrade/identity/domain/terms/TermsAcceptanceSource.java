package com.memetrade.identity.domain.terms;

/** u3-reacceptance (entities.md TermsAcceptance.source): where an acceptance of the terms came from. */
public enum TermsAcceptanceSource {
    /** Recorded with the account creation from an invitation; always names its invitation. */
    INVITE,
    /** Recorded by {@code POST /api/v1/terms/acceptances} after a new version; never names an invitation. */
    REACCEPTANCE
}
