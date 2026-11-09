package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.api.RetentionTableSource;
import com.memetrade.identity.domain.legal.DeploymentFields;
import com.memetrade.identity.domain.legal.LegalSettings;
import com.memetrade.identity.infra.TermsCatalog;
import com.memetrade.identity.infra.TermsProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Passo 8 (BR1.8 to BR1.10, BR1.15; infrastructure-specification.md "ETag por hash do conteúdo"): the answers of L1
 * are built once, with explicit nulls, the J5 table for the current version and a strong ETag per content.
 */
@Tag("u1-legal-documents")
class LegalDocumentsControllerViewTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final List<RetentionItem> PORT =
            List.of(new RetentionItem("Dado do outro módulo", "Finalidade", "1 mês", "Apagar"));

    @Test
    void theCurrentVersionUsesTheJ5TableAndAnOlderOneItsOwnDeclaredTable() {
        LegalDocumentsView view = view("classpath:terms-test/reviewed/", DeploymentFields.empty(), () -> PORT);

        assertThat(view.current().body().version()).isEqualTo("v2");
        assertThat(view.current().body().retention())
                .extracting(LegalDtos.RetentionItemResponse::dataItem)
                .containsExactly("Dado do outro módulo");
        assertThat(view.version("v1").orElseThrow().body().retention()).isEmpty();
        assertThat(view.version("v3")).isEmpty();
        assertThat(view.version(null)).isEmpty();
    }

    @Test
    void absentValuesAreExplicitNullsInTheJson() throws Exception {
        LegalDocumentsView view = view("classpath:terms-test/reviewed/", DeploymentFields.empty(), List::of);

        JsonNode legacy = JSON.valueToTree(view.version("v1").orElseThrow().body());
        JsonNode current = JSON.valueToTree(view.current().body());

        assertThat(legacy.has("privacy")).isTrue();
        assertThat(legacy.get("privacy").isNull()).isTrue();
        assertThat(legacy.get("terms").get("summary").isNull()).isTrue();
        assertThat(legacy.get("provisional").asBoolean()).isTrue();
        assertThat(current.get("deployment").has("dpoEmail")).isTrue();
        assertThat(current.get("deployment").get("dpoEmail").isNull()).isTrue();
        assertThat(current.get("publishable").asBoolean()).isFalse();
        assertThat(current.get("terms").get("summary")).hasSize(5);
    }

    @Test
    void theEtagIsAStrongHashOfTheContentStableAndDifferentWhenTheContentChanges() {
        LegalDocumentsView first = view("classpath:terms-test/reviewed/", DeploymentFields.empty(), List::of);
        LegalDocumentsView again = view("classpath:terms-test/reviewed/", DeploymentFields.empty(), List::of);
        LegalDocumentsView configured = view(
                "classpath:terms-test/reviewed/",
                new DeploymentFields("Brasil", "Não há", "encarregado@example.test"),
                List::of);

        assertThat(first.current().etag())
                .matches("\"[0-9a-f]{32}\"")
                .isEqualTo(again.current().etag());
        assertThat(configured.current().etag()).isNotEqualTo(first.current().etag());
        assertThat(first.version("v1").orElseThrow().etag())
                .isNotEqualTo(first.current().etag());
        assertThat(first.history().etag()).matches("\"[0-9a-f]{32}\"");
    }

    @Test
    void theHistoryListsTheCurrentAndOlderVersionsNewestFirstWithLegacyAsProvisional() {
        LegalDocumentsView view = view("classpath:terms-test/reviewed/", DeploymentFields.empty(), List::of);

        assertThat(view.history().body())
                .extracting(LegalDtos.LegalVersionRef::version)
                .containsExactly("v2", "v1");
        assertThat(view.history().body())
                .extracting(LegalDtos.LegalVersionRef::provisional)
                .containsExactly(false, true);
    }

    @Test
    void aLegacyCurrentVersionHasNoPolicyAndAnEmptyTable() {
        LegalDocumentsView view = view("classpath:terms-test/legacy-only/", DeploymentFields.empty(), List::of);

        assertThat(view.current().body().privacy()).isNull();
        assertThat(view.current().body().retention()).isEmpty();
        assertThat(view.current().body().terms().sections()).hasSize(1);
    }

    private static LegalDocumentsView view(String location, DeploymentFields fields, RetentionTableSource source) {
        TermsCatalog catalog = new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties(location),
                new LegalSettings(false, fields),
                CLOCK);
        return new LegalDocumentsView(catalog, source, JSON);
    }
}
