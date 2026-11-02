package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.shared.health.HealthStatuses;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Health component {@code marketSources} (NFR9.1, reliability-design.md, Q1), replacing the U1 {@code marketSource}:
 * UP while some source is OK or DEGRADED; DEGRADED (HTTP 200) when every source FAILED; UNKNOWN while every source is
 * still PENDING; never DOWN, so the container stays healthy and the list explains the failure instead. Outside the
 * readiness group. The details keep {@code lastUpdatedAt} (the latest success), which scripts/verify.sh waits for,
 * plus the state and last success of each source.
 */
@Component("marketSources")
public class MarketSourcesHealth implements HealthIndicator {

    private final MarketStore store;

    public MarketSourcesHealth(MarketStore store) {
        this.store = store;
    }

    @Override
    public Health health() {
        List<SourceStatus> sources;
        try {
            sources = store.sourceStatuses();
        } catch (DataAccessException unavailable) {
            return Health.status(HealthStatuses.DEGRADED)
                    .withDetail("error", unavailable.getClass().getSimpleName())
                    .build();
        }
        Health.Builder builder = Health.status(status(sources));
        Map<String, Map<String, String>> details = new LinkedHashMap<>();
        for (SourceStatus source : sources) {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("status", source.status().name());
            detail.put("simulated", Boolean.toString(source.source().simulated()));
            source.lastUpdatedAt().ifPresent(at -> detail.put("lastUpdatedAt", at.toString()));
            details.put(source.source().sourceId(), detail);
        }
        builder.withDetail("sources", details);
        latest(sources).ifPresent(at -> builder.withDetail("lastUpdatedAt", at.toString()));
        return builder.build();
    }

    static Status status(List<SourceStatus> sources) {
        if (sources.stream().anyMatch(s -> s.status().isAvailable())) {
            return Status.UP;
        }
        if (sources.stream().anyMatch(s -> s.status() == SourceStatus.State.FAILED)) {
            return HealthStatuses.DEGRADED;
        }
        return Status.UNKNOWN;
    }

    private static Optional<Instant> latest(List<SourceStatus> sources) {
        return sources.stream()
                .map(SourceStatus::lastUpdatedAt)
                .flatMap(Optional::stream)
                .max(Instant::compareTo);
    }
}
