package com.memetrade.identity.infra;

import com.memetrade.identity.domain.terms.TermsAcceptanceLookup;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * u3-reacceptance (NFR3.2, performance-design.md): the {@code EXISTS} of the reacceptance lock through the unique
 * constraint {@code uq_terms_acceptance_user_version (user_id, terms_version)}, filtered by the tenant of the
 * authentication. The timer {@code memetrade.terms.reacceptance.check} wraps ONLY this query and publishes a percentile
 * histogram, so the load scenario reads its p95 from {@code /actuator/prometheus}; it has no person or tenant label.
 */
@Repository
public class JdbcTermsAcceptanceLookup implements TermsAcceptanceLookup {

    static final String TIMER = "memetrade.terms.reacceptance.check";

    /** The query of the lock; public so the plan test explains exactly this text (NFR3.2). */
    public static final String SQL = """
            select exists (
                select 1 from terms_acceptance
                where user_id = :user and terms_version = :version and tenant_id = :tenant
            )
            """;

    private final JdbcClient jdbc;
    private final Timer timer;

    public JdbcTermsAcceptanceLookup(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.timer = Timer.builder(TIMER)
                .description("Duration of the acceptance check of the terms reacceptance lock")
                .publishPercentileHistogram()
                .register(registry);
    }

    @Override
    public boolean hasAccepted(UUID userId, UUID tenantId, String version) {
        Timer.Sample sample = Timer.start();
        try {
            Boolean exists = jdbc.sql(SQL)
                    .param("user", userId)
                    .param("version", version)
                    .param("tenant", tenantId)
                    .query(Boolean.class)
                    .single();
            return Boolean.TRUE.equals(exists);
        } finally {
            sample.stop(timer);
        }
    }
}
