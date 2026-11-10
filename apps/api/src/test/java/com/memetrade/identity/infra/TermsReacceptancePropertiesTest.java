package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Passo 4 of u3-reacceptance (BR3.2, infrastructure-specification.md "Configuração nova"): the allowed prefixes of the
 * reacceptance lock live in application.yml with the five prefixes of L3; an empty list or a prefix outside
 * {@code /api/v1/} stops the api, so the lock never blocks the way out nor opens a route outside the API by mistake.
 */
@Tag("u3-reacceptance")
class TermsReacceptancePropertiesTest {

    @Test
    void applicationYmlListsTheSixPrefixesOfL3() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        ReacceptanceProperties bound = Binder.get(environment)
                .bind("memetrade.terms.reacceptance", ReacceptanceProperties.class)
                .get();

        assertThat(bound.allowedPrefixes())
                .containsExactly(
                        "/api/v1/legal/",
                        "/api/v1/terms/acceptances",
                        "/api/v1/account",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/refresh",
                        // u4-data-requests (BR4.20): the data subject requests stay reachable during the lock.
                        "/api/v1/privacy/");
    }

    @Test
    void anEmptyListIsRefused() {
        assertThatThrownBy(() -> new ReacceptanceProperties(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> new ReacceptanceProperties(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPrefixOutsideTheApiOrTheWholeApiIsRefused() {
        assertThatThrownBy(() -> new ReacceptanceProperties(List.of("/actuator/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/actuator/");
        assertThatThrownBy(() -> new ReacceptanceProperties(List.of("/api/v1/")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefixesAreTrimmed() {
        assertThat(new ReacceptanceProperties(List.of("  /api/v1/privacy/ ")).allowedPrefixes())
                .containsExactly("/api/v1/privacy/");
    }
}
