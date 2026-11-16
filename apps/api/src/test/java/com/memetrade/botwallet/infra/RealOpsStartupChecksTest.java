package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.domain.Network;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Step 1 of the U5 (S8, S28, BR1.3, BR1.5, U5-NFR1.1, U5-NFR8.2): the start-up checks of the REAL operations and the
 * fixed settings of {@code memetrade.real.ops}.
 */
@Tag("u5-real-operations")
class RealOpsStartupChecksTest {

    private static final String FOUNDER = UUID.randomUUID().toString();
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static RealOpsStartupChecks.Inputs mainnet(boolean production, String founder, String ci, String sha) {
        return new RealOpsStartupChecks.Inputs(
                Network.MAINNET, production, true, founder, ci, sha, "owner/repo", "github-token-placeholder");
    }

    @Test
    void theLocalNetworkStartsWithAConfiguredCiAndWithoutTheFounder() {
        assertThat(RealOpsStartupChecks.violations(
                        new RealOpsStartupChecks.Inputs(Network.LOCALNET, false, true, "", "GREEN", "", "", "")))
                .isEmpty();
        assertThat(RealOpsStartupChecks.violations(
                        new RealOpsStartupChecks.Inputs(Network.DEVNET, false, false, null, "RED", null, null, null)))
                .isEmpty();
    }

    @Test
    void theMainnetStartsOnlyInProductionWithTheFounderTheShaAndTheGitHub() {
        assertThat(RealOpsStartupChecks.violations(mainnet(true, FOUNDER, "", SHA)))
                .isEmpty();
    }

    /** S8: the MAINNET outside the production profile does not start. */
    @Test
    void theMainnetOutsideProductionIsRefused() {
        assertThat(RealOpsStartupChecks.violations(mainnet(false, FOUNDER, "", SHA)))
                .singleElement()
                .asString()
                .contains("MAINNET needs the production profile");
    }

    /** S28: the MAINNET without founder-user-id does not start. */
    @Test
    void theMainnetWithoutTheFounderIsRefused() {
        assertThat(RealOpsStartupChecks.violations(mainnet(true, " ", "", SHA)))
                .singleElement()
                .asString()
                .contains("founder-user-id is required");
    }

    @Test
    void theMainnetRefusesAConfiguredCiAndAnImageWithoutItsSha() {
        List<String> violations = RealOpsStartupChecks.violations(mainnet(true, FOUNDER, "GREEN", ""));

        assertThat(violations).hasSize(2);
        assertThat(String.join(";", violations))
                .contains("MEMETRADE_REAL_OPS_CI is refused with the MAINNET")
                .contains("MEMETRADE_BUILD_SHA is required");
    }

    @Test
    void theWorkerOnTheMainnetNeedsTheRepositoryAndTheTokenButTheApiDoesNot() {
        RealOpsStartupChecks.Inputs worker =
                new RealOpsStartupChecks.Inputs(Network.MAINNET, true, true, FOUNDER, "", SHA, "", "");
        RealOpsStartupChecks.Inputs api =
                new RealOpsStartupChecks.Inputs(Network.MAINNET, true, false, FOUNDER, "", SHA, "", "");

        assertThat(RealOpsStartupChecks.violations(worker))
                .singleElement()
                .asString()
                .contains("MEMETRADE_GITHUB_ACTIONS_TOKEN")
                .doesNotContain("github-token");
        assertThat(RealOpsStartupChecks.violations(api)).isEmpty();
    }

    @Test
    void aConfiguredCiIsGreenOrRedAndTheFounderIsAUuid() {
        assertThat(RealOpsStartupChecks.violations(new RealOpsStartupChecks.Inputs(
                        Network.LOCALNET, false, true, "not-a-uuid", "YELLOW", "", "", "")))
                .hasSize(2)
                .anyMatch(message -> message.contains("GREEN or RED"))
                .anyMatch(message -> message.contains("must be a UUID"));
    }

    @Test
    void theOperationsAreFixedInApplicationYmlAndTheTokenComesOnlyFromTheEnvironment() throws IOException {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.reconciliation-threads")))
                .isEqualTo("4");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.gates-interval")))
                .isEqualTo("5m");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.reconciliation-interval")))
                .isEqualTo("${MEMETRADE_REAL_OPS_RECONCILIATION_INTERVAL:PT15M}");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.kill-switch-cancel-interval")))
                .isEqualTo("1m");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.gauges-interval")))
                .isEqualTo("30s");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.ci"))).isEqualTo("${MEMETRADE_REAL_OPS_CI:}");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.github.timeout")))
                .isEqualTo("5s");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.ops.github.token")))
                .isEqualTo("${MEMETRADE_GITHUB_ACTIONS_TOKEN:}");
        assertThat(String.valueOf(yml.getProperty("memetrade.build.sha"))).isEqualTo("${MEMETRADE_BUILD_SHA:}");
        assertThat(String.valueOf(yml.getProperty("memetrade.real.founder-user-id")))
                .isEqualTo("${MEMETRADE_REAL_FOUNDER_USER_ID:}");
    }

    @Test
    void theGitHubSettingsNeverPrintTheToken() {
        RealOpsProperties.Github github = new RealOpsProperties.Github(
                "https://api.github.com",
                "owner/repo",
                "ci.yml",
                "secret-token-value",
                java.time.Duration.ofSeconds(5));

        assertThat(github.toString()).doesNotContain("secret-token-value");
    }
}
