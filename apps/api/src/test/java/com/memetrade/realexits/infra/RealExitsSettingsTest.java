package com.memetrade.realexits.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.infra.RealProperties;
import com.memetrade.realexits.application.ExitsFlag;
import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Step 1 of the U7 (U7-NFR4.10, BR0.3, BR9.3, BR9.4, infrastructure-specification.md "Caminho simulado"): the exits are
 * off by default in every profile; the simulated adapter is refused outside the LOCALNET; the Jupiter key is never
 * printed.
 */
@Tag("u7-exits-and-fiscal")
class RealExitsSettingsTest {

    private static RealProperties real(Network network, RealProperties.Exits exits) {
        return new RealProperties(
                false, "v3", network, 1L, 1L, 1L, 1L, 1L, 1, 0L, false, null, null, null, null, exits);
    }

    @Test
    void theExitsAreOffByDefaultInTheSharedConfiguration() throws IOException {
        PropertySource<?> shipped = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(String.valueOf(shipped.getProperty("memetrade.real.exits.enabled")))
                .isEqualTo("${MEMETRADE_REAL_EXITS_ENABLED:false}");
        assertThat(String.valueOf(shipped.getProperty("memetrade.real.exits.adapter")))
                .isEqualTo("${MEMETRADE_REAL_EXITS_ADAPTER:jupiter}");
        assertThat(new ExitsFlag(new MockEnvironment()).exitsEnabled()).isFalse();
        assertThat(new ExitsFlag(new MockEnvironment().withProperty("memetrade.real.exits.enabled", "true"))
                        .exitsEnabled())
                .isTrue();
    }

    @Test
    void withoutTheExitsBlockTheDefaultIsOffWithTheRealAdapter() {
        RealProperties properties = real(Network.LOCALNET, null);

        assertThat(properties.exits()).isEqualTo(RealProperties.Exits.OFF);
        assertThat(properties.exits().enabled()).isFalse();
        assertThat(properties.exits().adapter()).isEqualTo("jupiter");
    }

    @Test
    void theSimulatedAdapterIsAcceptedOnlyOnTheLocalnet() {
        RealProperties.Exits simulated = new RealProperties.Exits(true, "simulated", "");

        assertThatCode(() -> real(Network.LOCALNET, simulated)).doesNotThrowAnyException();
        assertThatThrownBy(() -> real(Network.DEVNET, simulated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only on the LOCALNET");
        assertThatThrownBy(() -> real(Network.MAINNET, simulated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownAdapterIsRefused() {
        assertThatThrownBy(() -> new RealProperties.Exits(true, "wiremock", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jupiter or simulated");
    }

    @Test
    void theJupiterKeyIsNeverPrinted() {
        RealProperties.Exits exits = new RealProperties.Exits(false, "jupiter", "jup-secret-value-123");

        assertThat(exits.toString()).doesNotContain("jup-secret-value-123").contains("<masked>");
        assertThat(real(Network.LOCALNET, exits).toString()).doesNotContain("jup-secret-value-123");
    }
}
