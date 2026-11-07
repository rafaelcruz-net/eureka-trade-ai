package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.infra.BotProperties;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Passo 1 of U9 (NFR2.1, logical-components.md "Para o Infrastructure Design"): the cap of open live channels per
 * person is {@code memetrade.bots.stream.max-channels-per-person}, 5 in application.yml, and the configuration guard
 * accepts only 1 to 20 (a value out of its domain stops the process naming the property).
 */
@Tag("u9")
class U9SettingsTest {

    private static BotProperties.Stream stream(int maxChannelsPerPerson) {
        return new BotProperties.Stream(
                Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofMinutes(30), maxChannelsPerPerson);
    }

    @Test
    void theApplicationSettingsCapFiveChannelsPerPerson() throws IOException {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertThat(yaml.getProperty("memetrade.bots.stream.max-channels-per-person"))
                .hasToString("5");
    }

    @Test
    void theGuardAcceptsOneToTwentyChannels() {
        assertThatCode(() -> stream(1)).doesNotThrowAnyException();
        assertThatCode(() -> stream(5)).doesNotThrowAnyException();
        assertThatCode(() -> stream(20)).doesNotThrowAnyException();
    }

    @Test
    void theGuardRefusesAValueOutOfItsDomainNamingTheProperty() {
        assertThatThrownBy(() -> stream(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memetrade.bots.stream.max-channels-per-person");
        assertThatThrownBy(() -> stream(21)).hasMessageContaining("memetrade.bots.stream.max-channels-per-person");
    }
}
