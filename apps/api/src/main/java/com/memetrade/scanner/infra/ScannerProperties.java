package com.memetrade.scanner.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scanner settings of U4. {@code rateLimitPerMinute} ({@code SCANNER_RATE_LIMIT_PER_MINUTE}, 1 to 10000) is the
 * number of requests per person and per route in a sliding window of 60 s (NFR6.3); out of range stops the api with a
 * message naming the variable.
 */
@ConfigurationProperties(prefix = "memetrade.scanner")
public record ScannerProperties(int rateLimitPerMinute) {

    public static final int MAX_LIMIT = 10_000;

    public ScannerProperties {
        if (rateLimitPerMinute < 1 || rateLimitPerMinute > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "SCANNER_RATE_LIMIT_PER_MINUTE is out of range: expected between 1 and 10000");
        }
    }
}
