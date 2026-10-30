package com.memetrade.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled jobs of the API process (market collection, audit relay, idempotency cleanup).
 * Integration tests switch it off with {@code memetrade.scheduling.enabled=false} and drive jobs directly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
