package com.memetrade.identity.domain.terms;

import java.util.Optional;

/**
 * Port of the versioned terms (BR3.1, BR5.2): the current version and any version an account accepted. Since
 * u1-legal-documents (BR1.16) both follow the activation of the legal catalog: {@link #current()} is the EFFECTIVE
 * current version (never a marked version that was ignored) and {@link #find(String)} only finds versions of the
 * history.
 */
public interface CurrentTerms {

    /** The effective current version; the api never starts without one (NFR10.3, BR1.1). */
    TermsDocument current();

    /** A version of the history (current or earlier), to show what an account accepted (BR5.2, BR1.16). */
    Optional<TermsDocument> find(String version);
}
