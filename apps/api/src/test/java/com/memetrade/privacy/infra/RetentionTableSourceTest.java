package com.memetrade.privacy.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.api.RetentionTableSource;
import com.memetrade.identity.domain.legal.LegalSettings;
import com.memetrade.identity.infra.LegalConfig;
import com.memetrade.identity.infra.TermsCatalog;
import com.memetrade.identity.infra.TermsProperties;
import com.memetrade.privacy.domain.RetentionCatalog;
import com.memetrade.privacy.domain.RetentionRule;
import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Passo 8 of u2-retention (BR2.9, J5, F6; tests written after this layer): the Privacy module registers its
 * implementation of the retention port, which wins over the default of Identity and serves the rows of its catalog in
 * their order.
 */
@Tag("u2-retention")
class RetentionTableSourceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(TermsCatalog.class, RetentionTableSourceTest::catalog)
            .withUserConfiguration(PrivacyRetentionTableSource.class, LegalConfig.class);

    @Test
    void thePrivacySourceWinsAndTheDefaultOfIdentityIsNotCreated() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RetentionTableSource.class);
            assertThat(context.getBean(RetentionTableSource.class)).isInstanceOf(PrivacyRetentionTableSource.class);
            assertThat(context).doesNotHaveBean("catalogRetentionTableSource");
        });
    }

    @Test
    void itServesTheRowsOfTheCatalogInTheirOrderNotTheTableOfTheLegalCatalog() {
        runner.run(context -> assertThat(
                        context.getBean(RetentionTableSource.class).items())
                .containsExactlyElementsOf(RetentionCatalog.rules().stream()
                        .map(RetentionRule::toItem)
                        .toList())
                .extracting(RetentionItem::dataItem)
                .doesNotContain("Dado de teste A"));
    }

    @Test
    void itStartsWithOrWithoutDeclarationsOfPurges() {
        runner.withBean(
                        "sessionPurgeDeclaration",
                        RetentionEnforcerDeclaration.class,
                        () -> RetentionEnforcerDeclaration.of(
                                "sessionPurge", RetentionTerm.days(30), RetentionAnchor.ENDED))
                .run(context -> assertThat(
                                context.getBean(RetentionTableSource.class).items())
                        .hasSize(12));
    }

    @Test
    void theItemsAreTheSameImmutableListOnEveryCall() {
        PrivacyRetentionTableSource source = new PrivacyRetentionTableSource(
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(RetentionEnforcerDeclaration.class));

        assertThat(source.items()).isSameAs(source.items()).hasSize(12);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> source.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TermsCatalog catalog() {
        return new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties("classpath:terms-test/reviewed/"),
                LegalSettings.defaults(),
                CLOCK);
    }
}
