package com.memetrade.identity.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retention and cleanup of idempotency records (reliability-design.md Q6). */
@ConfigurationProperties(prefix = "memetrade.idempotency")
public record IdempotencyProperties(Duration defaultRetention, Duration cleanupInterval, int cleanupBatchSize) {}
