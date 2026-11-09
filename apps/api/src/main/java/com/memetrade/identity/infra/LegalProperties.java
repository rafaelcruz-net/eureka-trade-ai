package com.memetrade.identity.infra;

import com.memetrade.identity.domain.legal.DeploymentFields;
import com.memetrade.identity.domain.legal.LegalSettings;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the legal documents (memetrade.legal, infrastructure-specification.md "Configuração nova da api"):
 * the deployment fields ({@code MEMETRADE_LEGAL_STORAGE_LOCATION}, {@code MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER},
 * {@code MEMETRADE_LEGAL_DPO_EMAIL}; empty means "a definir"), the publishing requirement
 * ({@code MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE}, on only in production), the per-IP limit of the public routes
 * ({@code memetrade.legal.rate-limit-per-minute}, 60; raised only by the loadtest profile) and the CIDR list of the
 * internal networks exempt from it ({@code memetrade.legal.rate-limit.internal-networks}). None is a secret.
 */
@ConfigurationProperties(prefix = "memetrade.legal")
public record LegalProperties(
        String storageLocation,
        String internationalTransfer,
        String dpoEmail,
        Boolean requirePublishable,
        Integer rateLimitPerMinute,
        RateLimit rateLimit) {

    public static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 60;

    /** Upper bound of the limit, like the other limiters; the api refuses a value outside 1..100000. */
    public static final int MAX_RATE_LIMIT_PER_MINUTE = 100_000;

    public LegalProperties {
        requirePublishable = requirePublishable != null && requirePublishable;
        rateLimitPerMinute = rateLimitPerMinute == null ? DEFAULT_RATE_LIMIT_PER_MINUTE : rateLimitPerMinute;
        if (rateLimitPerMinute < 1 || rateLimitPerMinute > MAX_RATE_LIMIT_PER_MINUTE) {
            throw new IllegalArgumentException("memetrade.legal.rate-limit-per-minute"
                    + " (MEMETRADE_LEGAL_RATE_LIMIT_PER_MINUTE) must be between 1 and " + MAX_RATE_LIMIT_PER_MINUTE);
        }
        rateLimit = rateLimit == null ? new RateLimit(List.of()) : rateLimit;
    }

    /** {@code memetrade.legal.rate-limit.*}: the comma-separated CIDR list is read as a list by relaxed binding. */
    public record RateLimit(List<String> internalNetworks) {

        public RateLimit {
            internalNetworks = internalNetworks == null
                    ? List.of()
                    : internalNetworks.stream()
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .toList();
        }
    }

    /** The domain view used by the activation (BR1.1, BR1.6). */
    public LegalSettings settings() {
        return new LegalSettings(
                requirePublishable, new DeploymentFields(storageLocation, internationalTransfer, dpoEmail));
    }
}
