package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.identity.domain.legal.ActiveLegalDocuments;
import com.memetrade.identity.domain.legal.DeploymentFields;
import com.memetrade.identity.domain.legal.LegalSettings;
import com.memetrade.identity.domain.legal.LegalVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Passo 2 of u1-legal-documents (written BEFORE the code, team.md Testing Posture): activation of the legal catalog at
 * start-up with the injectable clock (BR1.1, BR1.2, BR1.3, BR1.6; AC1.3.1 to AC1.3.3). Every case builds its own
 * catalog in a temporary folder; no real text of v2 is used.
 */
@Tag("u1-legal-documents")
class TermsCatalogActivationTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DeploymentFields COMPLETE = new DeploymentFields(
            "Brasil (São Paulo)", "Não há transferência internacional", "encarregado@example.test");

    @TempDir
    Path dir;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(TermsCatalog.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(TermsCatalog.class)).detachAppender(logs);
    }

    @Test
    void aMarkedReviewedVersionBecomesTheCurrentVersionAndTheHistoryListsItFirst() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").marked().reviewed().effectiveAt("2026-06-01T00:00:00Z").write();

        TermsCatalog catalog = catalog(LegalSettings.defaults());

        ActiveLegalDocuments active = catalog.active();
        assertThat(active.current().version()).isEqualTo("v2");
        assertThat(catalog.current().version()).isEqualTo("v2");
        assertThat(active.history()).extracting(LegalVersion::version).containsExactly("v2", "v1");
        assertThat(active.history().get(1).legacy()).isTrue();
        assertThat(active.ignored()).isEmpty();
        assertThat(warnings()).isEmpty();
        assertThat(infos()).anyMatch(line -> line.contains("Legal documents loaded: current version v2, 2 version(s)"));
    }

    @Test
    void aMarkedVersionWithoutReviewFallsBackToThePreviousOneByEffectiveDateNeverByName() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").reviewed().effectiveAt("2026-03-01T00:00:00Z").write();
        version("v10").reviewed().effectiveAt("2026-06-01T00:00:00Z").write();
        version("v11").marked().effectiveAt("2026-09-01T00:00:00Z").write();

        TermsCatalog catalog = catalog(LegalSettings.defaults());

        // By name "v2" sorts after "v10"; by effective date v10 is the most recent activatable one.
        assertThat(catalog.active().current().version()).isEqualTo("v10");
        assertThat(catalog.active().history()).extracting(LegalVersion::version).containsExactly("v10", "v2", "v1");
        assertThat(catalog.active().ignored())
                .extracting(ignored -> ignored.version())
                .containsExactly("v11");
        assertThat(warnings()).containsExactly("Legal version v11 ignored: not legally reviewed");
    }

    @Test
    void theWarningOfAnIgnoredVersionNamesOnlyTheVersionAndTheReasonNeverTheText() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").marked().effectiveAt("2026-06-01T00:00:00Z").write();

        catalog(LegalSettings.defaults());

        assertThat(warnings()).hasSize(1);
        assertThat(String.join("\n", allLines()))
                .doesNotContain("Texto dos termos")
                .doesNotContain("Texto da política")
                .doesNotContain("encarregado@");
    }

    @Test
    void aVersionWithAFutureEffectiveDateIsIgnoredAndNeverListed() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").marked().reviewed().effectiveAt("2026-11-01T00:00:00Z").write();

        TermsCatalog catalog = catalog(LegalSettings.defaults());

        assertThat(catalog.active().current().version()).isEqualTo("v1");
        assertThat(catalog.active().history()).extracting(LegalVersion::version).containsExactly("v1");
        assertThat(warnings()).containsExactly("Legal version v2 ignored: effective in the future");
    }

    @Test
    void anUnmarkedVersionLaterThanTheCurrentOneIsNeitherCurrentNorListed() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", true);
        version("v2").reviewed().effectiveAt("2026-06-01T00:00:00Z").write();

        TermsCatalog catalog = catalog(LegalSettings.defaults());

        assertThat(catalog.active().current().version()).isEqualTo("v1");
        assertThat(catalog.active().history()).extracting(LegalVersion::version).containsExactly("v1");
    }

    @Test
    void whenPublishingIsRequiredAMissingFieldIgnoresTheNonLegacyVersionAndKeepsTheLegacyOne() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").marked().reviewed().effectiveAt("2026-06-01T00:00:00Z").write();
        DeploymentFields withoutDpo = new DeploymentFields("Brasil (São Paulo)", "Não há transferência", null);

        TermsCatalog catalog = catalog(new LegalSettings(true, withoutDpo));

        assertThat(catalog.active().current().version()).isEqualTo("v1");
        assertThat(catalog.active().publishable()).isFalse();
        assertThat(warnings()).containsExactly("Legal version v2 ignored: not publishable (missing deployment fields)");
    }

    @Test
    void whenPublishingIsRequiredAndEveryFieldIsPresentTheReviewedVersionIsCurrentAndPublishable() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").marked().reviewed().effectiveAt("2026-06-01T00:00:00Z").write();

        TermsCatalog catalog = catalog(new LegalSettings(true, COMPLETE));

        assertThat(catalog.active().current().version()).isEqualTo("v2");
        assertThat(catalog.active().publishable()).isTrue();
        assertThat(catalog.active().deployment()).isEqualTo(COMPLETE);
    }

    @Test
    void withoutPublishingRequiredAMissingFieldOnlyMakesTheCurrentVersionNotPublishable() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").marked().reviewed().effectiveAt("2026-06-01T00:00:00Z").write();

        TermsCatalog catalog = catalog(new LegalSettings(false, new DeploymentFields(" ", null, null)));

        assertThat(catalog.active().current().version()).isEqualTo("v2");
        assertThat(catalog.active().publishable()).isFalse();
        assertThat(catalog.active().deployment().storageLocation()).isNull();
    }

    @Test
    void withoutAnyActivatableVersionTheApiDoesNotStartAndSaysWhy() throws IOException {
        version("v2").marked().effectiveAt("2026-06-01T00:00:00Z").write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The api cannot start without valid terms")
                .hasMessageContaining("no activatable version")
                .hasMessageContaining("v2: not legally reviewed");
    }

    @Test
    void anOlderActivatableVersionIsRequiredForTheFallbackALaterOneNeverReplacesTheMarkedOne() throws IOException {
        version("v2").marked().effectiveAt("2026-03-01T00:00:00Z").write();
        version("v3").reviewed().effectiveAt("2026-06-01T00:00:00Z").write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no activatable version");
    }

    @Test
    void aTextLargerThan200KilobytesStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        String big = "## Seção\n\n" + "a".repeat(200 * 1024);
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .terms(big)
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms.txt of v2 is larger than 200 KB");
    }

    @Test
    void aVersionWithMoreThan100SectionsStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 99; i++) {
            many.append("## Seção ").append(i).append("\n\nTexto.\n\n");
        }
        // 99 sections in the terms plus the sections of the privacy policy and the risk notice exceed 100.
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .terms(many.toString())
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v2 has more than 100 sections");
    }

    @Test
    void aNonLegacyVersionWithoutThePrivacyPolicyStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .without("privacy.txt")
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version v2 has no privacy.txt");
    }

    @Test
    void aNonLegacyVersionWithoutASummaryStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .without("privacy-summary.txt")
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version v2 has no privacy-summary.txt");
    }

    @Test
    void aNonLegacyVersionWithoutTheRetentionTableStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .without("retention.tsv")
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version v2 has no retention.tsv");
    }

    @Test
    void aSummaryWithFourTopicsStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .termsSummary(topics(4))
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms-summary.txt of v2 must have 5 to 7 topics, found 4");
    }

    @Test
    void aSummaryWithEightTopicsStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .privacySummary(topics(8))
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("privacy-summary.txt of v2 must have 5 to 7 topics, found 8");
    }

    @Test
    void aReviewWithOnlyTheDateOrOnlyTheReviewerStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2")
                .marked()
                .effectiveAt("2026-06-01T00:00:00Z")
                .extra("legalReviewedAt=2026-10-20\n")
                .write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v2 must have both legalReviewedAt and legalReviewedBy, or neither");
    }

    @Test
    void aVersionWithoutEffectiveDateStopsTheStart() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2").marked().reviewed().effectiveAt(null).write();

        assertThatThrownBy(() -> catalog(LegalSettings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v2 has no valid effectiveAt");
    }

    @Test
    void theTextsAreSplitIntoSectionsWithUniqueAnchorsAndTheRetentionTableIsRead() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        version("v2")
                .marked()
                .reviewed()
                .effectiveAt("2026-06-01T00:00:00Z")
                .terms(
                        "## Uso do serviço\n\nPrimeiro parágrafo\ncom duas linhas.\n\nSegundo.\n\n## Uso do serviço\n\nOutro.")
                .write();

        LegalVersion current = catalog(LegalSettings.defaults()).active().current();

        assertThat(current.terms().sections()).hasSize(2);
        assertThat(current.terms().sections().get(0).id()).isEqualTo("uso-do-servico");
        assertThat(current.terms().sections().get(1).id()).isEqualTo("uso-do-servico-2");
        assertThat(current.terms().sections().get(0).paragraphs())
                .containsExactly("Primeiro parágrafo com duas linhas.", "Segundo.");
        assertThat(current.terms().summary()).hasSize(5);
        assertThat(current.privacy()).isNotNull();
        assertThat(current.retention()).hasSize(2);
        assertThat(current.retention().getFirst().dataItem()).isEqualTo("Endereço da carteira");
        assertThat(current.legalReviewedBy()).isEqualTo("Assessoria de teste");
    }

    @Test
    void aLegacyVersionWithoutHeadingsBecomesOneSectionWithoutPolicySummaryOrTable() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", true);

        LegalVersion current = catalog(LegalSettings.defaults()).active().current();

        assertThat(current.legacy()).isTrue();
        assertThat(current.privacy()).isNull();
        assertThat(current.terms().summary()).isEmpty();
        assertThat(current.terms().sections()).hasSize(1);
        assertThat(current.terms().sections().getFirst().paragraphs()).containsExactly("Termos antigos.", "Mais.");
        assertThat(current.retention()).isEmpty();
    }

    // ----- fixtures -----

    private TermsCatalog catalog(LegalSettings settings) {
        return new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties(dir.toUri().toString()),
                settings,
                CLOCK);
    }

    private void legacy(String name, String effectiveAt, boolean marked) throws IOException {
        Path folder = Files.createDirectories(dir.resolve(name));
        Files.writeString(
                folder.resolve("version.properties"),
                "version=" + name + "\ncurrent=" + marked + "\nlegacy=true\neffectiveAt=" + effectiveAt + "\n");
        Files.writeString(folder.resolve("terms.txt"), "Termos antigos.\n\nMais.", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("risk-notice.txt"), "Aviso antigo.", StandardCharsets.UTF_8);
    }

    private VersionBuilder version(String name) {
        return new VersionBuilder(name);
    }

    private static String topics(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            text.append("Tópico ").append(i).append('\n');
        }
        return text.toString();
    }

    private List<String> warnings() {
        return lines(Level.WARN);
    }

    private List<String> infos() {
        return lines(Level.INFO);
    }

    private List<String> lines(Level level) {
        List<String> found = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            if (event.getLevel() == level) {
                found.add(event.getFormattedMessage());
            }
        }
        return found;
    }

    private List<String> allLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** A non-legacy version folder, valid unless a test changes one file. */
    private final class VersionBuilder {

        private final String name;
        private boolean marked;
        private boolean reviewed;
        private String effectiveAt = "2026-06-01T00:00:00Z";
        private String extra = "";
        private String terms = "## Uso do serviço\n\nTexto dos termos.";
        private String privacy = "## Dados que tratamos\n\nTexto da política.\n\n## Seus direitos\n\nTexto.";
        private String riskNotice = "## Riscos\n\nTexto do aviso.";
        private String termsSummary = topics(5);
        private String privacySummary = topics(6);
        private String retention = "dataItem\tpurpose\tperiod\tendAction\n"
                + "Endereço da carteira\tEntrar no beta\tEnquanto a conta existir\tAnonimizar\n"
                + "# comentário ignorado\n"
                + "Aceite dos termos\tProvar o aceite\t5 anos\tExcluir\n";
        private final List<String> missing = new ArrayList<>();

        private VersionBuilder(String name) {
            this.name = name;
        }

        VersionBuilder marked() {
            this.marked = true;
            return this;
        }

        VersionBuilder reviewed() {
            this.reviewed = true;
            return this;
        }

        VersionBuilder effectiveAt(String value) {
            this.effectiveAt = value;
            return this;
        }

        VersionBuilder extra(String properties) {
            this.extra = properties;
            return this;
        }

        VersionBuilder terms(String value) {
            this.terms = value;
            return this;
        }

        VersionBuilder termsSummary(String value) {
            this.termsSummary = value;
            return this;
        }

        VersionBuilder privacySummary(String value) {
            this.privacySummary = value;
            return this;
        }

        VersionBuilder without(String file) {
            missing.add(file);
            return this;
        }

        void write() throws IOException {
            Path folder = Files.createDirectories(dir.resolve(name));
            StringBuilder properties = new StringBuilder("version=" + name + "\ncurrent=" + marked + "\n");
            if (effectiveAt != null) {
                properties.append("effectiveAt=").append(effectiveAt).append('\n');
            }
            if (reviewed) {
                properties.append("legalReviewedAt=2026-10-20\nlegalReviewedBy=Assessoria de teste\n");
            }
            properties.append(extra);
            Files.writeString(folder.resolve("version.properties"), properties.toString());
            file(folder, "terms.txt", terms);
            file(folder, "privacy.txt", privacy);
            file(folder, "risk-notice.txt", riskNotice);
            file(folder, "terms-summary.txt", termsSummary);
            file(folder, "privacy-summary.txt", privacySummary);
            file(folder, "retention.tsv", retention);
        }

        private void file(Path folder, String file, String content) throws IOException {
            if (!missing.contains(file)) {
                Files.writeString(folder.resolve(file), content, StandardCharsets.UTF_8);
            }
        }
    }
}
