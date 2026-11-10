package com.memetrade.identity.infra;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * u3-reacceptance (BR3.2, L3, infrastructure-specification.md "Configuração nova"): the path prefixes a guest still
 * reaches while it has not accepted the effective current terms (legal documents, the acceptance itself, Account,
 * logout and refresh; U4 adds {@code /api/v1/privacy/}). Lives in {@code application.yml}, never in an environment
 * variable. Every prefix starts with {@code /api/v1/}; an empty list is refused, so the lock never blocks the way out.
 */
@ConfigurationProperties(prefix = "memetrade.terms.reacceptance")
public record ReacceptanceProperties(List<String> allowedPrefixes) {

    static final String API_PREFIX = "/api/v1/";

    public ReacceptanceProperties {
        if (allowedPrefixes == null || allowedPrefixes.isEmpty()) {
            throw new IllegalArgumentException("memetrade.terms.reacceptance.allowed-prefixes must not be empty");
        }
        allowedPrefixes = allowedPrefixes.stream().map(String::trim).toList();
        for (String prefix : allowedPrefixes) {
            if (!prefix.startsWith(API_PREFIX) || prefix.length() == API_PREFIX.length()) {
                throw new IllegalArgumentException(
                        "memetrade.terms.reacceptance.allowed-prefixes must name paths under /api/v1/: " + prefix);
            }
        }
    }
}
