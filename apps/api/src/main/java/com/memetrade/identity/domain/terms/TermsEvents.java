package com.memetrade.identity.domain.terms;

/**
 * u3-reacceptance (NFR8.1): what the lock and the acceptance count. The implementation keeps counters without any
 * label of person or tenant.
 */
public interface TermsEvents {

    /** The lock refused a request with {@code 403 TERMS_REACCEPTANCE_REQUIRED}. */
    void blocked();

    /** An acceptance was inserted (never for a repeated acceptance). */
    void accepted(TermsAcceptanceSource source);
}
