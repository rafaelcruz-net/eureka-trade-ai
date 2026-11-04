package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.domain.terms.TermsDocument;
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
 * Passo 4 (BR6.2, NFR6.13): the catalog of prohibited terms of U4 (apps/web/src/content/prohibited-terms.json, the
 * same file the frontend test reads) runs over the terms and the risk notice of every version; a hit blocks the build.
 * Comparison ignores case and accents, like the frontend check.
 */
@Tag("u3")
class TermsProhibitedTermsTest {

    private static final Path CATALOG = Path.of("..", "web", "src", "content", "prohibited-terms.json");

    @Test
    void neitherTheTermsNorTheRiskNoticeContainAProhibitedTerm() throws IOException {
        TermsDocument current = new TermsCatalog(
                        new PathMatchingResourcePatternResolver(), new TermsProperties("classpath:terms/"))
                .current();
        List<String> terms = prohibitedTerms();

        assertThat(terms).contains("lucro garantido", "sem risco", "100% seguro", "detecta golpes");
        assertThat(hits(current.termsText(), terms)).isEmpty();
        assertThat(hits(current.riskNoticeText(), terms)).isEmpty();
    }

    @Test
    void theCheckFindsAPlantedTermWhateverTheCaseAndAccents() throws IOException {
        assertThat(hits("Operação SEM RÍSCO e Lucro Garantido", prohibitedTerms()))
                .containsExactlyInAnyOrder("sem risco", "lucro garantido");
    }

    private static List<String> prohibitedTerms() throws IOException {
        JsonNode catalog = new ObjectMapper().readTree(Files.readString(CATALOG));
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
