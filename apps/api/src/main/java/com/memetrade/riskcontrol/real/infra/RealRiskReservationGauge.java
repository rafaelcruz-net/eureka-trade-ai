package com.memetrade.riskcontrol.real.infra;

import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U3 (U3-NFR5.4, logical-components.md RealRiskMetrics): the gauge
 * {@code real_risk_reservations{state}}, only in the worker, refreshed by a task of its own every 30 s and never during
 * the scrape (the scrape reads the last values). A failed refresh keeps the last values and is logged.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RealRiskReservationGauge {

    private static final Logger log = LoggerFactory.getLogger(RealRiskReservationGauge.class);

    static final String NAME = "real_risk_reservations";
    static final List<String> STATES = List.of("OPEN", "HANDED_OVER", "EXPIRED");

    private final JdbcRealRiskStore store;
    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

    public RealRiskReservationGauge(JdbcRealRiskStore store, MeterRegistry registry) {
        this.store = store;
        for (String state : STATES) {
            AtomicLong value = new AtomicLong();
            values.put(state, value);
            Gauge.builder(NAME, value, AtomicLong::get)
                    .description("Reservations of the REAL barrier by state")
                    .tag("state", state)
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "30s", initialDelayString = "10s")
    public void refresh() {
        try {
            store.reservationsByState().forEach((state, count) -> {
                AtomicLong value = values.get(state);
                if (value != null) {
                    value.set(count);
                }
            });
        } catch (RuntimeException failure) {
            log.warn(
                    "REAL risk reservation gauge not refreshed: exceptionClass={}",
                    failure.getClass().getSimpleName());
        }
    }
}
