package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.CiStatus;
import com.memetrade.botwallet.infra.github.ConfiguredCiStatus;
import com.memetrade.botwallet.infra.github.GitHubCiStatus;
import com.memetrade.shared.config.ProcessRole;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Wiring of the REAL operations (261113-trading-real-bot U5). The settings are checked once at start-up in the api and
 * in the worker ({@link RealOpsStartupChecks}); a violation stops the context naming the setting, never a secret.
 */
@Configuration(proxyBeanMethods = false)
public class RealOpsConfig {

    /** The checked settings of the REAL operations; the beans of the U5 depend on them. */
    @Bean
    public CheckedOps realOpsCheckedSettings(
            RealProperties real,
            RealOpsProperties ops,
            Environment environment,
            @Value("${memetrade.build.sha:}") String buildSha) {
        boolean worker =
                ProcessRole.Kind.parse(environment.getProperty(ProcessRole.PROPERTY)) == ProcessRole.Kind.WORKER;
        List<String> violations = RealOpsStartupChecks.violations(new RealOpsStartupChecks.Inputs(
                real.network(),
                environment.acceptsProfiles(Profiles.of("production")),
                worker,
                real.founderUserId(),
                ops.ci(),
                buildSha,
                ops.github() == null ? null : ops.github().repository(),
                ops.github() == null ? null : ops.github().token()));
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "The REAL operations settings are invalid: " + String.join("; ", violations));
        }
        return new CheckedOps(ops, buildSha == null ? "" : buildSha.trim());
    }

    /**
     * BR1.4 (Q5): the CI of the gates. A configured value ({@code GREEN} or {@code RED}) outside the MAINNET; otherwise
     * the GitHub with the read-only token of the worker (the api never calls it: the gates run in the worker).
     */
    @Bean
    public CiStatus ciStatus(CheckedOps ops) {
        String configured = ops.properties().ci();
        if (configured != null && !configured.isBlank()) {
            return new ConfiguredCiStatus(configured);
        }
        RealOpsProperties.Github github = ops.properties().github();
        return GitHubCiStatus.create(
                github.apiUrl(), github.repository(), github.workflow(), github.token(), github.timeout());
    }

    /** The settings after the start-up check, with the commit of this image. */
    public record CheckedOps(RealOpsProperties properties, String buildSha) {}
}
