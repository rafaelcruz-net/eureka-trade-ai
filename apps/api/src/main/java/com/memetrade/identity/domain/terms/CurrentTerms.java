package com.memetrade.identity.domain.terms;

import java.util.Optional;

/** Port of the versioned terms (BR3.1, BR5.2): the current version and any version an account accepted. */
public interface CurrentTerms {

    /** The single current version; the api never starts without one (NFR10.3). */
    TermsDocument current();

    /** A known version, current or not, to show what an account accepted (BR5.2). */
    Optional<TermsDocument> find(String version);
}
