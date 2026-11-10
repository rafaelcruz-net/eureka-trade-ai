package com.memetrade.shared.retention;

import java.util.Objects;

/**
 * J7 (contract-summary.md, BR2.17): what a purge declares of itself, so the retention catalog of the Privacy module can
 * be checked against what the code applies. Each module that purges registers one bean per purge in its own
 * configuration, with the period read from the property the purge itself uses; the Privacy module only receives the
 * list. The contract lives in {@code shared}: no module depends on Privacy and Privacy depends on none of them, so there
 * is no cycle. A declaration exists in the api context even when its purge runs in the worker.
 */
public interface RetentionEnforcerDeclaration {

    /** The name of the purge, as in its lock or health component (for example {@code loginChallengePurge}). */
    String purgeId();

    /** The period the purge applies, read from its own configuration. */
    RetentionTerm retention();

    /** From when the period counts. */
    RetentionAnchor anchor();

    /** A declaration with fixed values. */
    static RetentionEnforcerDeclaration of(String purgeId, RetentionTerm retention, RetentionAnchor anchor) {
        return new Declared(purgeId, retention, anchor);
    }

    /** The value form of a declaration. */
    record Declared(String purgeId, RetentionTerm retention, RetentionAnchor anchor)
            implements RetentionEnforcerDeclaration {

        public Declared {
            Objects.requireNonNull(retention, "retention");
            Objects.requireNonNull(anchor, "anchor");
            if (purgeId == null || purgeId.isBlank()) {
                throw new IllegalArgumentException("A retention declaration needs the id of its purge");
            }
        }
    }
}
