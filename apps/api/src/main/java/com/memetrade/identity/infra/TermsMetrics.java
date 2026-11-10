package com.memetrade.identity.infra;

import com.memetrade.identity.domain.terms.TermsAcceptanceSource;
import com.memetrade.identity.domain.terms.TermsEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * u3-reacceptance (NFR8.1, monitoring-design.md): {@code memetrade.terms.reacceptance.blocked} (requests the lock
 * refused) and {@code memetrade.terms.acceptances} by {@code source}. No label of person, tenant or version.
 */
@Component
public class TermsMetrics implements TermsEvents {

    static final String BLOCKED = "memetrade.terms.reacceptance.blocked";
    static final String ACCEPTANCES = "memetrade.terms.acceptances";

    private final Counter blocked;
    private final Map<TermsAcceptanceSource, Counter> acceptances = new EnumMap<>(TermsAcceptanceSource.class);

    public TermsMetrics(MeterRegistry registry) {
        this.blocked = Counter.builder(BLOCKED)
                .description("Requests refused with 403 TERMS_REACCEPTANCE_REQUIRED")
                .register(registry);
        for (TermsAcceptanceSource source : TermsAcceptanceSource.values()) {
            acceptances.put(
                    source,
                    Counter.builder(ACCEPTANCES)
                            .description("Acceptances of the terms recorded, by source")
                            .tag("source", source.name())
                            .register(registry));
        }
    }

    @Override
    public void blocked() {
        blocked.increment();
    }

    @Override
    public void accepted(TermsAcceptanceSource source) {
        acceptances.get(source).increment();
    }
}
