package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.legal.DeploymentFields;
import com.memetrade.identity.domain.legal.LegalSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Passo 11 (NFR7.3, NFR8.2): {@code legalDocuments} is always UP, with two details only: the version and the publishable flag. */
@Tag("u1-legal-documents")
class LegalDocumentsHealthIndicatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void withoutADeploymentFieldTheComponentStaysUpAndFlagsTheVersionAsNotPublishable() {
        Health health = indicator(DeploymentFields.empty()).health();

        // Never DEGRADED: the status order ranks DEGRADED before UP and would degrade the overall health (NFR7.3).
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsOnlyKeys("currentVersion", "publishable")
                .containsEntry("currentVersion", "v2")
                .containsEntry("publishable", false);
    }

    @Test
    void withEveryDeploymentFieldTheComponentIsUpAndNeverShowsTheFields() {
        Health health = indicator(new DeploymentFields("Brasil", "Não há", "encarregado@example.test"))
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("publishable", true);
        assertThat(health.toString()).doesNotContain("encarregado@").doesNotContain("Brasil");
    }

    private static LegalDocumentsHealthIndicator indicator(DeploymentFields fields) {
        return new LegalDocumentsHealthIndicator(new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties("classpath:terms-test/reviewed/"),
                new LegalSettings(false, fields),
                CLOCK));
    }
}
