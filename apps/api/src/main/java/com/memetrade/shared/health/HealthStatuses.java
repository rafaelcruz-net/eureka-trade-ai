package com.memetrade.shared.health;

import org.springframework.boot.actuate.health.Status;

/**
 * Custom health status: a lagging audit relay or a source that stopped collecting is DEGRADED (HTTP 200, the
 * container stays healthy); only a missing database or pending migrations make readiness DOWN.
 */
public final class HealthStatuses {

    public static final Status DEGRADED = new Status("DEGRADED");

    private HealthStatuses() {}
}
