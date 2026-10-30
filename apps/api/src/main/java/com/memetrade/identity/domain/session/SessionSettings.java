package com.memetrade.identity.domain.session;

import java.time.Duration;

/** BR1.9: access token 15 minutes; refresh 8 hours since last use; session 12 hours since creation. */
public record SessionSettings(Duration accessTokenTtl, Duration refreshTtl, Duration sessionMaxAge) {}
