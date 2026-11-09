package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.domain.legal.LegalSection;
import com.memetrade.identity.domain.legal.LegalText;
import com.memetrade.identity.domain.legal.LegalVersion;
import com.memetrade.identity.infra.LegalCatalogReader.LoadedVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Passo 6 (BR1.5; US1.1, AC1.1.2, AC1.1.3): the privacy policy of every non-legacy version of the real catalog has each
 * required section and the PAPER notice, and no text of any version uses a term of the versioned list of prohibited
 * promises (apps/web/src/content/prohibited-terms.json, the same list the screens use). Comparison ignores case and
 * accents, like the frontend check and TermsProhibitedTermsTest.
 */
@Tag("u1-legal-documents")
class LegalTextContentTest {

    private static final Path PROHIBITED = Path.of("..", "web", "src", "content", "prohibited-terms.json");

    /** Anchors of the required sections of the policy (FR1.2, BR1.5). */
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "dados-que-tratamos-e-finalidade",
            "base-legal",
            "retencao",
            "compartilhamento",
            "seus-direitos-e-como-pedir",
            "encarregado-de-dados",
            "local-de-armazenamento-e-transferencia-internacional",
            "nao-usamos-rastreamento");

    @Test
    void theRealCatalogHasTheDraftV2NextToTheLegacyV1() {
        List<LegalVersion> versions = realVersions();

        assertThat(versions).extracting(LegalVersion::version).contains("v1", "v2");
        assertThat(version(versions, "v1").legacy()).isTrue();
        assertThat(version(versions, "v2").legacy()).isFalse();
        assertThat(nonLegacy(versions)).isNotEmpty();
    }

    @Test
    void everyNonLegacyPolicyHasEachRequiredSection() {
        for (LegalVersion version : nonLegacy(realVersions())) {
            assertThat(version.privacy().sections())
                    .as("privacy.txt of %s", version.version())
                    .extracting(LegalSection::id)
                    .containsAll(REQUIRED_SECTIONS);
            assertThat(version.privacy().summary()).hasSizeBetween(5, 7);
            assertThat(version.terms().summary()).hasSizeBetween(5, 7);
        }
    }

    @Test
    void everyNonLegacyPolicyCarriesThePaperSimulationNotice() {
        for (LegalVersion version : nonLegacy(realVersions())) {
            String policy = normalize(String.join(" ", allText(version.privacy())));
            assertThat(policy)
                    .as("privacy.txt of %s", version.version())
                    .contains("paper")
                    .contains("simulad");
            assertThat(normalize(String.join(" ", version.privacy().summary()))).contains("paper");
        }
    }

    @Test
    void everyNonLegacyVersionDeclaresItsRetentionTable() {
        for (LegalVersion version : nonLegacy(realVersions())) {
            assertThat(version.retention())
                    .as("retention.tsv of %s", version.version())
                    .isNotEmpty();
            assertThat(version.retention())
                    .extracting(RetentionItem::period)
                    .contains("12 meses", "180 dias", "30 dias", "24 horas");
        }
    }

    @Test
    void noTextOfAnyVersionUsesAProhibitedTerm() throws IOException {
        List<String> prohibited = prohibitedTerms();
        assertThat(prohibited).contains("lucro garantido", "sem risco", "100% seguro", "detecta golpes");

        for (LegalVersion version : realVersions()) {
            List<String> texts = new ArrayList<>(allText(version.terms()));
            texts.addAll(allText(version.riskNotice()));
            if (version.privacy() != null) {
                texts.addAll(allText(version.privacy()));
            }
            for (RetentionItem row : version.retention()) {
                texts.add(String.join(" ", row.dataItem(), row.purpose(), row.period(), row.endAction()));
            }
            assertThat(hits(String.join("\n", texts), prohibited))
                    .as(version.version())
                    .isEmpty();
        }
    }

    @Test
    void theCheckFindsAPlantedPromiseWhateverTheCaseAndAccents() throws IOException {
        assertThat(hits("Um INVESTIMENTO SEGURO com retôrno garantido", prohibitedTerms()))
                .containsExactlyInAnyOrder("investimento seguro", "retorno garantido");
    }

    private static List<LegalVersion> realVersions() {
        return LegalCatalogReader.read(new PathMatchingResourcePatternResolver(), "classpath:terms/").stream()
                .map(LoadedVersion::legal)
                .toList();
    }

    private static List<LegalVersion> nonLegacy(List<LegalVersion> versions) {
        return versions.stream().filter(version -> !version.legacy()).toList();
    }

    private static LegalVersion version(List<LegalVersion> versions, String name) {
        return versions.stream()
                .filter(item -> item.version().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> allText(LegalText text) {
        List<String> all = new ArrayList<>(text.summary());
        for (LegalSection section : text.sections()) {
            all.add(section.title());
            all.addAll(section.paragraphs());
        }
        return all;
    }

    private static List<String> prohibitedTerms() throws IOException {
        JsonNode catalog = new ObjectMapper().readTree(Files.readString(PROHIBITED));
        List<String> terms = new ArrayList<>();
        catalog.get("terms").forEach(term -> terms.add(term.asText()));
        catalog.get("proposedTerms").forEach(term -> terms.add(term.asText()));
        return terms;
    }

    private static List<String> hits(String text, List<String> terms) {
        String normalized = normalize(text);
        return terms.stream()
                .filter(term -> normalized.contains(normalize(term)))
                .toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
