package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 261121-paper-real-unico U4, Etapa 23, T20 (written before the rules; decision F1 of the founder; NFR7.6, NFR2.12,
 * NFR2.18): {@code promtool test rules} runs the ten cases of
 * {@code infra/observability/tests/real-ops-own-holdings-unavailable.test.yml} against the rules of the REAL
 * operations, in the image of {@code prom/prometheus} the ci.yml pins by digest (read from its step
 * {@code Alert rules (promtool)}, never a tag). An exit other than 0 fails with the output of the promtool.
 */
@Tag("u4-own-quantity-cycle")
class RealOpsOwnHoldingsAlertRuleIT {

    private static final Path ROOT = Path.of("..", "..");

    @Test
    void theTenCasesOfTheOwnHoldingsAlertsPassInThePromtoolOfTheCi() throws Exception {
        String image =
                CiPromtoolDigest.of(Files.readString(ROOT.resolve(".github/workflows/ci.yml"), StandardCharsets.UTF_8));

        try (GenericContainer<?> promtool = new GenericContainer<>(DockerImageName.parse(image))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(
                                ROOT.resolve("infra/observability").toAbsolutePath()),
                        "/rules")
                .withCreateContainerCmdModifier(command -> command.withEntrypoint("promtool"))
                .withCommand("test", "rules", "/rules/tests/real-ops-own-holdings-unavailable.test.yml")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
            String output;
            int exit;
            try {
                promtool.start();
                exit = promtool.getCurrentContainerInfo()
                        .getState()
                        .getExitCodeLong()
                        .intValue();
                output = promtool.getLogs();
            } catch (RuntimeException failed) {
                output = promtool.getLogs();
                throw new AssertionError("promtool test rules failed:\n" + output, failed);
            }
            assertThat(exit).as(output).isZero();
            assertThat(output).contains("SUCCESS");
        }
    }
}
