package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.api.RetentionTableSource;
import com.memetrade.identity.domain.legal.LegalSettings;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Passo 7 (BR1.8, J5): the default retention source of Identity is registered only when no other module registers
 * one; with another implementation registered, it wins and the default does not exist.
 */
@Tag("u1-legal-documents")
class RetentionTableSourceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);
    private static final List<RetentionItem> OTHER =
            List.of(new RetentionItem("Pedido de titular", "Atender", "12 meses", "Apagar"));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(TermsCatalog.class, () -> catalog("classpath:terms-test/reviewed/"))
            .withUserConfiguration(LegalConfig.class);

    @Test
    void withoutAnotherSourceTheDefaultReturnsTheTableOfTheCurrentVersion() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RetentionTableSource.class);
            assertThat(context.getBean(RetentionTableSource.class)).isInstanceOf(CatalogRetentionTableSource.class);
            assertThat(context.getBean(RetentionTableSource.class).items())
                    .extracting(RetentionItem::dataItem)
                    .containsExactly("Dado de teste A", "Dado de teste B");
        });
    }

    @Test
    void anotherRegisteredSourceWinsAndTheDefaultIsNotCreated() {
        runner.withBean("privacyRetentionCatalog", RetentionTableSource.class, () -> () -> OTHER)
                .run(context -> {
                    assertThat(context).hasSingleBean(RetentionTableSource.class);
                    assertThat(context).doesNotHaveBean("catalogRetentionTableSource");
                    assertThat(context.getBean(RetentionTableSource.class).items())
                            .isEqualTo(OTHER);
                });
    }

    @Test
    void aLegacyCurrentVersionWithoutATableGivesAnEmptyTable() {
        new ApplicationContextRunner()
                .withBean(TermsCatalog.class, () -> catalog("classpath:terms-test/legacy-only/"))
                .withUserConfiguration(LegalConfig.class)
                .run(context -> assertThat(
                                context.getBean(RetentionTableSource.class).items())
                        .isEmpty());
    }

    private static TermsCatalog catalog(String location) {
        return new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties(location),
                LegalSettings.defaults(),
                CLOCK);
    }
}
