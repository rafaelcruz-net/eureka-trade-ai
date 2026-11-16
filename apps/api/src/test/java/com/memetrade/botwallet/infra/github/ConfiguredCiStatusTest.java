package com.memetrade.botwallet.infra.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.domain.CiResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U5 (BR1.4, infrastructure-specification.md "Configuração do teste"): outside the MAINNET the CI may be
 * a configured value (GREEN in the local overlay and the e2e); it answers the same for any commit.
 */
@Tag("u5-real-operations")
class ConfiguredCiStatusTest {

    @Test
    void aConfiguredGreenIsGreenForAnyCommitEvenWithoutSha() {
        ConfiguredCiStatus green = new ConfiguredCiStatus("GREEN");

        assertThat(green.check("abc").status()).isEqualTo(CiResult.Status.GREEN);
        assertThat(green.check("").status()).isEqualTo(CiResult.Status.GREEN);
        assertThat(green.check("abc").runRef()).contains("configured");
    }

    @Test
    void aConfiguredRedIsRed() {
        assertThat(new ConfiguredCiStatus(" RED ").check("abc").status()).isEqualTo(CiResult.Status.RED);
    }

    @Test
    void anyOtherValueIsRefused() {
        assertThatThrownBy(() -> new ConfiguredCiStatus("YELLOW")).hasMessageContaining("GREEN or RED");
    }
}
