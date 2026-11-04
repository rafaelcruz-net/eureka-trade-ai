package com.memetrade.identity.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the versioned terms and risk notice live (memetrade.terms, U3 BR3.1). Each version is a folder holding
 * {@code version.properties}, {@code terms.txt} and {@code risk-notice.txt}; the default is {@code classpath:terms/}.
 */
@ConfigurationProperties(prefix = "memetrade.terms")
public record TermsProperties(String location) {

    public static final String DEFAULT_LOCATION = "classpath:terms/";

    public TermsProperties {
        location = location == null || location.isBlank() ? DEFAULT_LOCATION : location.trim();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
    }
}
