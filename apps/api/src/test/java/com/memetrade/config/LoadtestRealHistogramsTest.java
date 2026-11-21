package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.actuate.autoconfigure.metrics.PropertiesMeterFilter;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 261113-trading-real-bot (operation/performance-validation, scenario {@code real} of scripts/load-test.sh): only the
 * loadtest profile publishes the buckets of the REAL jobs ({@code real_ops_job}, {@code real_risk_job}), so the report
 * shows their p50, p95 and p99. The keys keep their "_" (bracket notation), the map of application.yml is merged and not
 * replaced, and without the profile (production) these timers keep publishing no bucket.
 */
@Tag("performance-validation")
class LoadtestRealHistogramsTest {

    private static StandardEnvironment environment(String... files) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // Later files win, as a profile file does over application.yml.
        for (String file : files) {
            loader.load(file, new ClassPathResource(file))
                    .forEach(source -> environment.getPropertySources().addFirst(source));
        }
        return environment;
    }

    private static Map<String, Boolean> histograms(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind(
                        "management.metrics.distribution.percentiles-histogram",
                        Bindable.mapOf(String.class, Boolean.class))
                .orElse(Map.of());
    }

    /**
     * Whether the meter filter Spring Boot builds from these settings turns the percentile histogram on for a timer of
     * this name. The Prometheus registry publishes those buckets; the simple registry would not, so the filter's
     * decision is what is checked.
     */
    private static boolean publishesBuckets(StandardEnvironment environment, String meter) {
        MetricsProperties properties = Binder.get(environment)
                .bind("management.metrics", MetricsProperties.class)
                .orElseGet(MetricsProperties::new);
        Meter.Id id = new Meter.Id(meter, Tags.of("job", "gates"), null, null, Meter.Type.TIMER);
        DistributionStatisticConfig config =
                new PropertiesMeterFilter(properties).configure(id, DistributionStatisticConfig.DEFAULT);
        return Boolean.TRUE.equals(config == null ? null : config.isPercentileHistogram());
    }

    @Test
    void theLoadtestProfileAddsTheRealJobsWithTheirUnderscoresAndKeepsTheBaseEntries() throws IOException {
        Map<String, Boolean> merged = histograms(environment("application.yml", "application-loadtest.yml"));

        assertThat(merged)
                .containsEntry("real_ops_job", true)
                .containsEntry("real_risk_job", true)
                .containsEntry("http.server.requests", true)
                .containsEntry("hikaricp.connections.acquire", true);
    }

    @Test
    void withTheLoadtestProfileTheRealJobTimersPublishBuckets() throws IOException {
        StandardEnvironment loadtest = environment("application.yml", "application-loadtest.yml");

        assertThat(publishesBuckets(loadtest, "real_ops_job")).isTrue();
        assertThat(publishesBuckets(loadtest, "real_risk_job")).isTrue();
        // Only the two names: a REAL timer that is not listed keeps its own configuration.
        assertThat(publishesBuckets(loadtest, "real_ops_job_other")).isFalse();
    }

    @Test
    void withoutTheLoadtestProfileTheRealJobTimersPublishNoBucket() throws IOException {
        StandardEnvironment production = environment("application.yml");

        assertThat(histograms(production)).doesNotContainKeys("real_ops_job", "real_risk_job");
        assertThat(publishesBuckets(production, "real_ops_job")).isFalse();
        assertThat(publishesBuckets(production, "real_risk_job")).isFalse();
    }
}
