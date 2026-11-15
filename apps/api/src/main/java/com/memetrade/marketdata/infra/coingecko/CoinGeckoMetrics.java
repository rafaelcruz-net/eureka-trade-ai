package com.memetrade.marketdata.infra.coingecko;

import com.memetrade.marketdata.api.MarketField;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/**
 * Metrics of the CoinGecko source (U2 observability-design.md, NFR5.1 to NFR5.5). Labels come only from closed lists
 * (source, kind, outcome, field): never the key, a mint, a URL or a person.
 *
 * <ul>
 *   <li>{@code marketdata_provider_calls_total{source,kind,outcome}}: kind {@code price|discovery|detail|usage},
 *       outcome {@code ok|retry|error|limited};
 *   <li>{@code marketdata_round_seconds{source,kind}}: duration of the price, discovery and detail parts of a round;
 *   <li>{@code marketdata_observations_total{field}} and {@code marketdata_invalid_values_total{field}}: every value
 *       read and every value refused (BR1.2);
 *   <li>the gauges {@code marketdata_active_tokens} and {@code marketdata_oldest_price_age_seconds} are registered by
 *       the adapter; {@code marketdata_provider_budget_ratio} and {@code marketdata_provider_conservative_mode} by
 *       {@link CallBudget}.
 * </ul>
 */
public class CoinGeckoMetrics {

    /** Outcome label of one call. */
    public enum Outcome {
        OK,
        RETRY,
        ERROR,
        LIMITED;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry meters;
    private final String source;

    public CoinGeckoMetrics(MeterRegistry meters, String source) {
        this.meters = meters;
        this.source = source;
    }

    public MeterRegistry registry() {
        return meters;
    }

    public void call(CallBudget.Kind kind, Outcome outcome) {
        Counter.builder("marketdata_provider_calls_total")
                .tag("source", source)
                .tag("kind", kind.label())
                .tag("outcome", outcome.label())
                .register(meters)
                .increment();
    }

    public void round(String kind, Duration duration) {
        Timer.builder("marketdata_round_seconds")
                .tag("source", source)
                .tag("kind", kind)
                .register(meters)
                .record(duration);
    }

    public void observation(MarketField field) {
        Counter.builder("marketdata_observations_total")
                .tag("field", field.name())
                .register(meters)
                .increment();
    }

    public void invalid(MarketField field) {
        Counter.builder("marketdata_invalid_values_total")
                .tag("field", field.name())
                .register(meters)
                .increment();
    }
}
