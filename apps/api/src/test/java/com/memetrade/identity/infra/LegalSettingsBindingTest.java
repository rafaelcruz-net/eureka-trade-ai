package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.legal.LegalSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Passo 12 (infrastructure-specification.md "Configuração nova da api", NFR6.3, NFR8.2): binding of memetrade.legal,
 * including the comma-separated CIDR list read as a list, the defaults of application.yml, the high limit of the
 * loadtest profile only, and the health groups that leave {@code legalDocuments} out.
 */
@Tag("u1-legal-documents")
class LegalSettingsBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void theCommaSeparatedCidrListBindsAsAListWithoutBlanks() {
        runner.withPropertyValues(
                        "memetrade.legal.rate-limit.internal-networks=172.28.1.0/24, 10.0.0.0/8,,",
                        "memetrade.legal.rate-limit-per-minute=120")
                .run(context -> {
                    LegalProperties properties = context.getBean(LegalProperties.class);
                    assertThat(properties.rateLimit().internalNetworks())
                            .containsExactly("172.28.1.0/24", "10.0.0.0/8");
                    assertThat(properties.rateLimitPerMinute()).isEqualTo(120);
                });
    }

    @Test
    void withoutSettingsTheDefaultsAre60PerMinuteNoExemptionAndNothingPublishable() {
        runner.run(context -> {
            LegalProperties properties = context.getBean(LegalProperties.class);
            assertThat(properties.rateLimitPerMinute()).isEqualTo(60);
            assertThat(properties.rateLimit().internalNetworks()).isEmpty();
            LegalSettings settings = properties.settings();
            assertThat(settings.requirePublishable()).isFalse();
            assertThat(settings.deployment().complete()).isFalse();
        });
    }

    @Test
    void theDeploymentFieldsAndThePublishingRequirementBindAndBlanksAreAbsent() {
        runner.withPropertyValues(
                        "memetrade.legal.storage-location=Brasil (São Paulo)",
                        "memetrade.legal.international-transfer= ",
                        "memetrade.legal.dpo-email=encarregado@example.test",
                        "memetrade.legal.require-publishable=true")
                .run(context -> {
                    LegalSettings settings =
                            context.getBean(LegalProperties.class).settings();
                    assertThat(settings.requirePublishable()).isTrue();
                    assertThat(settings.deployment().storageLocation()).isEqualTo("Brasil (São Paulo)");
                    assertThat(settings.deployment().internationalTransfer()).isNull();
                    assertThat(settings.deployment().dpoEmail()).isEqualTo("encarregado@example.test");
                });
    }

    @Test
    void aLimitOutsideTheBoundsStopsTheStartNamingTheVariable() {
        runner.withPropertyValues("memetrade.legal.rate-limit-per-minute=0").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("MEMETRADE_LEGAL_RATE_LIMIT_PER_MINUTE"));
    }

    @Test
    void theApplicationFilesReadTheVariablesAndOnlyTheLoadtestProfileRaisesTheLimit() throws Exception {
        PropertySource<?> application = yaml("application.yml");
        PropertySource<?> loadtest = yaml("application-loadtest.yml");

        assertThat(application.getProperty("memetrade.legal.storage-location"))
                .hasToString("${MEMETRADE_LEGAL_STORAGE_LOCATION:}");
        assertThat(application.getProperty("memetrade.legal.international-transfer"))
                .hasToString("${MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER:}");
        assertThat(application.getProperty("memetrade.legal.dpo-email")).hasToString("${MEMETRADE_LEGAL_DPO_EMAIL:}");
        assertThat(application.getProperty("memetrade.legal.require-publishable"))
                .hasToString("${MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE:false}");
        assertThat(application.getProperty("memetrade.legal.rate-limit.internal-networks"))
                .hasToString("${MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS:}");
        assertThat(application.getProperty("memetrade.legal.rate-limit-per-minute"))
                .hasToString("60");
        assertThat(Integer.parseInt(String.valueOf(loadtest.getProperty("memetrade.legal.rate-limit-per-minute"))))
                .isGreaterThanOrEqualTo(10_000);
    }

    @Test
    void theLegalDocumentsComponentIsInNeitherTheLivenessNorTheReadinessGroup() throws Exception {
        PropertySource<?> application = yaml("application.yml");

        assertThat(String.valueOf(application.getProperty("management.endpoint.health.group.readiness.include")))
                .doesNotContain("legalDocuments");
        assertThat(application.getProperty("management.endpoint.health.group.liveness.include"))
                .isNull();
        assertThat(application.getProperty("management.endpoint.health.status.http-mapping.DEGRADED"))
                .hasToString("200");
    }

    private static PropertySource<?> yaml(String file) throws Exception {
        return new YamlPropertySourceLoader()
                .load(file, new ClassPathResource(file))
                .getFirst();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LegalProperties.class)
    static class Binding {}
}
