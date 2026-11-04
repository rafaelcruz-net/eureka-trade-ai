package com.memetrade.ledger.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * Passo 23 (cicd-pipeline.md): the beans of the load cycle driver are registered only when the {@code loadtest}
 * profile is active. Component scanning evaluates {@code @Profile} exactly as the application does, so the default and
 * the {@code test} profile see none of them, and {@code test,loadtest} sees all of them.
 */
@Tag("u7")
class LoadtestBeansProfileTest {

    private static final Set<String> LOAD_BEANS = Set.of(
            LoadtestCycleAuthContext.class.getName(),
            LoadtestCycleDriver.class.getName(),
            LoadtestLedgerHealthIndicator.class.getName(),
            LoadtestLedgerSeed.class.getName(),
            LoadtestTokens.class.getName());

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
        return scanner.findCandidateComponents("com.memetrade.ledger.loadtest").stream()
                .map(definition -> definition.getBeanClassName())
                .collect(Collectors.toSet());
    }
}
