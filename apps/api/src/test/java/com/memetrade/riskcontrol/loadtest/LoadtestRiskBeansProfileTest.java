package com.memetrade.riskcontrol.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * Passo 16 of U6 (cicd-pipeline.md): the beans of the scenario {@code u6} are registered only when the
 * {@code loadtest} profile is active; component scanning evaluates {@code @Profile} exactly as the application does.
 */
@Tag("u6")
class LoadtestRiskBeansProfileTest {

    private static final Set<String> LOAD_BEANS = Set.of(
            LoadtestRiskCycleDriver.class.getName(),
            LoadtestRiskSeed.class.getName(),
            LoadtestRiskPurgeTrigger.class.getName(),
            LoadtestRiskHealthIndicator.class.getName());

    @Test
    void theDefaultProfileRegistersNoLoadBean() {
        assertThat(candidates()).isEmpty();
    }

    @Test
    void theTestProfileAloneRegistersNoLoadBean() {
        assertThat(candidates("test")).isEmpty();
    }

    @Test
    void theLoadtestProfileRegistersEveryLoadBean() {
        assertThat(candidates("test", "loadtest")).isEqualTo(LOAD_BEANS);
    }

    private static Set<String> candidates(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true, environment);
        return scanner.findCandidateComponents("com.memetrade.riskcontrol.loadtest").stream()
                .map(definition -> definition.getBeanClassName())
                .collect(Collectors.toSet());
    }
}
