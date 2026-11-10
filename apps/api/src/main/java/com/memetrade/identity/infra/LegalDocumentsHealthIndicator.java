package com.memetrade.identity.infra;

import com.memetrade.identity.domain.legal.ActiveLegalDocuments;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health component {@code legalDocuments} (NFR7.3, NFR8.2): always UP, with the effective current version and the
 * {@code publishable} flag as details; a version that is not publishable (a deployment field is missing, "a definir")
 * shows {@code publishable=false}. The component never degrades the overall health, since the status order ranks
 * DEGRADED before UP and the overall status must stay UP (NFR7.3, decision of the project owner at the u1 skeleton
 * checkpoint); the LEGAL_NOT_PUBLISHABLE alert reads the flag. It is in neither the liveness nor the readiness group.
 * The details carry only the version and the flag, never a text nor a deployment field (NFR1.1).
 */
public class LegalDocumentsHealthIndicator implements HealthIndicator {

    private final TermsCatalog catalog;

    public LegalDocumentsHealthIndicator(TermsCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Health health() {
        ActiveLegalDocuments active = catalog.active();
        return Health.up()
                .withDetail("currentVersion", active.current().version())
                .withDetail("publishable", active.publishable())
                .build();
    }
}
