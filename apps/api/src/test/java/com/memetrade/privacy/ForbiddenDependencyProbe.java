package com.memetrade.privacy;

import com.memetrade.identity.infra.TermsCatalog;

/**
 * Test-only probe of {@code PrivacyArchitectureTest}: a class of the Privacy package that reaches an internal class of
 * Identity, so the rule is shown to catch a violation (it is never part of the production classes).
 */
final class ForbiddenDependencyProbe {

    private ForbiddenDependencyProbe() {}

    static String reachIntoIdentity(TermsCatalog catalog) {
        return catalog.current().version();
    }
}
