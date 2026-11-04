package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Passo 4 (BR3.1, NFR10.3, NFR13.1): the api starts only with exactly one current version with non-empty texts. */
@Tag("u3")
class TermsCatalogTest {

    @TempDir
    Path dir;

    @Test
    void theVersionedV1OfTheRepositoryIsTheCurrentVersionAndIsProvisional() {
        TermsCatalog catalog = catalog("classpath:terms/");

        assertThat(catalog.current().version()).isEqualTo("v1");
        assertThat(catalog.current().termsText()).startsWith("Versão provisória, pendente de revisão jurídica.");
        assertThat(catalog.current().riskNoticeText())
                .startsWith("Versão provisória, pendente de revisão jurídica.")
                .contains("O modo REAL está indisponível no beta.");
        assertThat(catalog.current().termsText() + catalog.current().riskNoticeText())
                .doesNotContain("\r")
                .doesNotContain("<");
        assertThat(catalog.find("v1")).contains(catalog.current());
        assertThat(catalog.find("v0")).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
    }

    @Test
    void anOlderVersionStaysReadableWhileTheNewOneIsCurrent() throws IOException {
        version("v1", false, "Termos antigos", "Aviso antigo");
        version("v2", true, "Termos novos\r\ncom CRLF", "Aviso novo");

        TermsCatalog catalog = catalog(location());

        assertThat(catalog.current().version()).isEqualTo("v2");
        assertThat(catalog.current().termsText()).isEqualTo("Termos novos\ncom CRLF");
        assertThat(catalog.find("v1").orElseThrow().termsText()).isEqualTo("Termos antigos");
    }

    @Test
    void withoutACurrentVersionTheApiDoesNotStart() throws IOException {
        version("v1", false, "Termos", "Aviso");

        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one version must have current=true, found 0");
    }

    @Test
    void withTwoCurrentVersionsTheApiDoesNotStart() throws IOException {
        version("v1", true, "Termos", "Aviso");
        version("v2", true, "Termos", "Aviso");

        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 2");
    }

    @Test
    void anEmptyTermsOrRiskNoticeTextStopsTheStart() throws IOException {
        version("v1", true, "   \n", "Aviso");

        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty terms.txt");

        Path other = Files.createDirectories(dir.resolve("other").resolve("v1"));
        Files.writeString(other.resolve("version.properties"), "version=v1\ncurrent=true\n");
        Files.writeString(other.resolve("terms.txt"), "Termos");
        Files.writeString(other.resolve("risk-notice.txt"), "");
        assertThatThrownBy(() -> catalog(dir.resolve("other").toUri().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty risk-notice.txt");
    }

    @Test
    void aMissingFolderOrFileStopsTheStartWithAClearMessage() throws IOException {
        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no version folder");

        Path v1 = Files.createDirectories(dir.resolve("v1"));
        Files.writeString(v1.resolve("version.properties"), "version=v1\ncurrent=true\n");
        Files.writeString(v1.resolve("terms.txt"), "Termos");
        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no risk-notice.txt");
    }

    @Test
    void anInvalidOrRepeatedVersionNameStopsTheStart() throws IOException {
        version("a", true, "Termos", "Aviso");
        Path bad = Files.createDirectories(dir.resolve("b"));
        Files.writeString(bad.resolve("version.properties"), "version=v 1\ncurrent=false\n");
        Files.writeString(bad.resolve("terms.txt"), "Termos");
        Files.writeString(bad.resolve("risk-notice.txt"), "Aviso");

        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version.properties must name a version");

        Files.writeString(bad.resolve("version.properties"), "version=a\ncurrent=false\n");
        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appears more than once");
    }

    @Test
    void aTextThatIsNotUtf8StopsTheStart() throws IOException {
        Path v1 = Files.createDirectories(dir.resolve("v1"));
        Files.writeString(v1.resolve("version.properties"), "version=v1\ncurrent=true\n");
        Files.write(v1.resolve("terms.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});
        Files.writeString(v1.resolve("risk-notice.txt"), "Aviso");

        assertThatThrownBy(() -> catalog(location()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not UTF-8");
    }

    @Test
    void aContextWithAnInvalidTermsFolderFailsToStart() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(TermsProperties.class, () -> new TermsProperties(location()))
                .withBean(TermsCatalog.class, () -> catalog(location()))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("The api cannot start without valid terms"));
    }

    private String location() {
        return dir.toUri().toString();
    }

    private void version(String name, boolean current, String terms, String risk) throws IOException {
        Path folder = Files.createDirectories(dir.resolve(name));
        Files.writeString(folder.resolve("version.properties"), "version=" + name + "\ncurrent=" + current + "\n");
        Files.writeString(folder.resolve("terms.txt"), terms, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("risk-notice.txt"), risk, StandardCharsets.UTF_8);
    }

    private static TermsCatalog catalog(String location) {
        return new TermsCatalog(new PathMatchingResourcePatternResolver(), new TermsProperties(location));
    }
}
