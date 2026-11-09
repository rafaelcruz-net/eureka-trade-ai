package com.memetrade.identity.infra;

import com.memetrade.identity.domain.legal.ActiveLegalDocuments;
import com.memetrade.shared.health.HealthStatuses;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health component {@code legalDocuments} (NFR7.3, NFR8.2): UP when the effective current version is publishable,
 * DEGRADED otherwise (a deployment field is missing, "a definir"). DEGRADED answers HTTP 200 and the component is in
 * neither the liveness nor the readiness group, so it never takes the api down. The details carry only the version and
 * the flag, never a text nor a deployment field (NFR1.1).
 */
public class LegalDocumentsHealthIndicator implements HealthIndicator {

    private final TermsCatalog catalog;

    public LegalDocumentsHealthIndicator(TermsCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Health health() {
        ActiveLegalDocuments active = catalog.active();
        Health.Builder builder = active.publishable() ? Health.up() : Health.status(HealthStatuses.DEGRADED);
        return builder.withDetail("currentVersion", active.current().version())
                .withDetail("publishable", active.publishable())
                .build();
    }
}
