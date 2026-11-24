package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 23 (written before the rules; F1, NFR7.6): the image of the T20, without Docker:
 * the ci.yml pins exactly one digest of {@code prom/prometheus} in its step {@code Alert rules (promtool)}; none, two
 * distinct ones, or no step at all fail with the reason.
 */
@Tag("u4-own-quantity-cycle")
class CiPromtoolDigestTest {

    private static final String DIGEST =
            "prom/prometheus@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996";
    private static final String OTHER =
            "prom/prometheus@sha256:0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    void theCiPinsExactlyOneDigestOfPrometheus() throws Exception {
        String ci = Files.readString(Path.of("..", "..", ".github", "workflows", "ci.yml"), StandardCharsets.UTF_8);

        assertThat(CiPromtoolDigest.of(ci)).matches("prom/prometheus@sha256:[0-9a-f]{64}");
    }

    @Test
    void theSameDigestRepeatedIsStillOne() {
        assertThat(CiPromtoolDigest.of(ci("docker run " + DIGEST + " a\n          docker run " + DIGEST + " b")))
                .isEqualTo(DIGEST);
    }

    @Test
    void twoDistinctDigestsOrNoneFailWithTheReason() {
        assertThatThrownBy(() -> CiPromtoolDigest.of(ci("docker run " + DIGEST + " a " + OTHER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 1")
                .hasMessageContaining("found 2");
        assertThatThrownBy(() -> CiPromtoolDigest.of(ci("docker run prom/prometheus:latest check")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 0");
    }

    @Test
    void aMissingStepFailsWithTheReason() {
        String ci = """
                jobs:
                  backend:
                    steps:
                      - name: Build
                        run: echo build
                """;

        assertThatThrownBy(() -> CiPromtoolDigest.of(ci))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Alert rules (promtool)");
    }

    private static String ci(String run) {
        return """
                jobs:
                  backend:
                    steps:
                      - name: Alert rules (promtool)
                        run: |
                          %s
                """.formatted(run);
    }
}
