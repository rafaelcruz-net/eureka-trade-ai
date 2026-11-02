package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Fails the build if the default profile ever ships a login limit other than 10 (security-design.md, Q2). */
@Tag("u1")
class ProfileDefaultsTest {

    @Test
    void theDefaultProfileLimitsLoginTo10PerMinute() throws IOException {
        assertThat(property("application.yml", "memetrade.auth.login-rate-limit-per-minute"))
                .isEqualTo(10);
    }

    @Test
    void onlyTheLoadtestProfileRaisesTheLimitAndTheTestProfileDoesNot() throws IOException {
        assertThat(String.valueOf(property("application-loadtest.yml", "memetrade.auth.login-rate-limit-per-minute")))
                .contains("LOGIN_RATE_LIMIT_PER_MINUTE");
        assertThat(property("application-test.yml", "memetrade.auth.login-rate-limit-per-minute"))
                .isNull();
    }

    /** U4 infrastructure-specification.md (Q1): the pool grows from 10 to 20, still with the 2 s wait. */
    @Test
    void theDefaultProfileHasPoolOf20WithA2SecondWaitAndNoSeededAccount() throws IOException {
        assertThat(property("application.yml", "spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("${DB_POOL_MAX_SIZE:20}");
        assertThat(property("application.yml", "spring.datasource.hikari.connection-timeout"))
                .isEqualTo(2000);
        assertThat(property("application.yml", "memetrade.smoke.beta-wallet-address"))
                .isNull();
        assertThat(property("application.yml", "spring.jackson.deserialization.fail-on-unknown-properties"))
                .isEqualTo(true);
    }

    /** NFR6.3: the API (default profile) never migrates and never receives the owner credential. */
    @Test
    @Tag("u2")
    void theDefaultProfileHasLiquibaseOffAndNoOwnerCredential() throws IOException {
        assertThat(property("application.yml", "spring.liquibase.enabled")).isEqualTo(false);
        assertThat(property("application.yml", "spring.liquibase.user")).isNull();
        assertThat(property("application.yml", "spring.liquibase.password")).isNull();
        String yaml = new String(
                new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(yaml).doesNotContain("DB_OWNER_USER").doesNotContain("DB_OWNER_PASSWORD");
    }

    /** NFR6.3: only the migrate profile carries the owner login, and it starts no web server and no scheduler. */
    @Test
    @Tag("u2")
    void onlyTheMigrateProfileUsesTheOwnerLogin() throws IOException {
        assertThat(String.valueOf(property("application-migrate.yml", "spring.liquibase.user")))
                .isEqualTo("${DB_OWNER_USER}");
        assertThat(property("application-migrate.yml", "spring.liquibase.enabled"))
                .isEqualTo(true);
        assertThat(property("application-migrate.yml", "spring.main.web-application-type"))
                .isEqualTo("none");
        assertThat(property("application-migrate.yml", "memetrade.scheduling.enabled"))
                .isEqualTo(false);
        for (String other : List.of("application-test.yml", "application-loadtest.yml")) {
            String yaml = new String(
                    new ClassPathResource(other).getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(yaml).as(other).doesNotContain("DB_OWNER_");
        }
    }

    /** The retention pool login comes from .env; the default never carries a password. */
    @Test
    @Tag("u2")
    void theRetentionLoginHasNoDefaultPassword() throws IOException {
        assertThat(String.valueOf(property("application.yml", "memetrade.audit.retention-db.password")))
                .isEqualTo("${DB_RETENTION_PASSWORD:}");
        assertThat(property("application.yml", "memetrade.audit.jobs.retention-months"))
                .isEqualTo("${AUDIT_RETENTION_MONTHS:12}");
    }

    private static Object property(String file, String name) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        Object value = sources.getFirst().getProperty(name);
        if (value instanceof org.springframework.boot.origin.OriginTrackedValue tracked) {
            value = tracked.getValue();
        }
        return value instanceof CharSequence text ? text.toString() : value;
    }
}
