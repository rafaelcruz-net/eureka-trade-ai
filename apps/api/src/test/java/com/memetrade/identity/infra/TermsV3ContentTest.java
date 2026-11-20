package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.identity.domain.legal.LegalSection;
import com.memetrade.identity.domain.legal.LegalSettings;
import com.memetrade.identity.domain.legal.LegalText;
import com.memetrade.identity.domain.legal.LegalVersion;
import com.memetrade.identity.infra.LegalCatalogReader.LoadedVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Step 19 of u6-real-modes-ui (Build and Test loop-back 2; AC1.1.3, FR9.1, BR2.1), written BEFORE the texts: the v3 of
 * the REAL catalog says that the person can lose all the value deposited in the bot wallet, that the bot wallet has a
 * shared responsibility (named and explained) and that there is no promise of profit; no file of v3 keeps the PAPER-only
 * claims of v1/v2; v3 is the only version marked current and, by the owner's decision of 2026-11-20, it is reviewed and
 * effective from 2026-11-20 (before that instant v1 stays effective, as in the integration tests' fixed clock).
 */
@Tag("u1-legal-documents")
class TermsV3ContentTest {

    private static final String CATALOG = "classpath:terms/";
    private static final List<String> TEXT_FILES = List.of(
            "terms.txt", "risk-notice.txt", "privacy.txt", "terms-summary.txt", "privacy-summary.txt", "retention.tsv");

    /** PAPER-only claims of v1/v2 that are false once REAL exists (accent-free, lower case). */
    private static final List<String> PAPER_ONLY_CLAIMS = List.of(
            "tudo e simulado",
            "tudo no beta e simulado",
            "todas as operacoes sao simuladas",
            "real esta indisponivel",
            "nao movimentamos fundos",
            "nenhuma operacao usa dinheiro real",
            "nenhuma ordem e enviada a rede",
            "rascunho");

    @Test
    void theTermsOfV3CarryTheThreeStatementsOfAc113() {
        LegalVersion v3 = version("v3");
        String terms = normalize(String.join(" ", allText(v3.terms())));

        assertThat(terms)
                .as("loss of everything deposited")
                .contains("voce pode perder todo o valor depositado na carteira do bot");
        assertThat(terms).as("shared responsibility, named").contains("responsabilidade compartilhada");
        assertThat(terms).as("no promise of profit").contains("nao ha promessa de lucro");
        assertThat(normalize(String.join(" ", allText(v3.riskNotice()))))
                .contains("perder todo o valor depositado")
                .contains("nao ha promessa de lucro");
        assertThat(normalize(String.join(" ", v3.terms().summary())))
                .contains("perder todo o valor depositado")
                .contains("responsabilidade compartilhada")
                .contains("nao ha promessa de lucro");
    }

    @Test
    void theSharedResponsibilityIsExplainedInItsOwnSectionSayingWhatIsOursAndWhatIsYours() {
        LegalSection shared = version("v3").terms().sections().stream()
                .filter(section -> section.id().startsWith("responsabilidade-compartilhada"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("v3 terms.txt has no 'Responsabilidade compartilhada' section"));
        String text = normalize(String.join(" ", shared.paragraphs()));

        assertThat(shared.paragraphs()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(text).contains("nossa parte").contains("sua parte").contains("kms");
    }

    @Test
    void theTermsOfV3DescribeTheProductAsItExists() {
        LegalVersion v3 = version("v3");
        String terms = normalize(String.join(" ", allText(v3.terms())));

        assertThat(terms)
                .contains("paper")
                .contains("saldo virtual")
                .contains("carteira do bot")
                .contains("kms")
                .contains("nunca e exportada")
                .contains("teto")
                .contains("carteira principal")
                .contains("kill switch")
                .contains("assinada")
                .contains("automatica nas saidas")
                .contains("automatica total")
                .contains("jupiter")
                .contains("taxas da rede")
                .contains("unknown")
                .contains("quarentena de 24 horas")
                .contains("frase secreta")
                .contains("nao autoriza nenhuma operacao");
    }

    /**
     * review-03 R-10, R-11: RealRiskPolicy checks the cap, the order size, the daily loss, the buys of the day, the
     * assessment and block of the token and the freshness of the data only on a BUY; a sell passes only the state
     * checks. A token with unknown data is never bought, and a position already held can still be sold.
     */
    @Test
    void theLimitsAreStatedForBuysAndTheSalesAreNeverBlockedByThem() {
        String terms = normalize(String.join(" ", allText(version("v3").terms())));

        assertThat(terms)
                .contains("antes de cada compra do real, conferimos o teto, o limite por ordem, a perda maxima do dia,"
                        + " o numero de compras do dia, a avaliacao e o bloqueio do token e o frescor dos dados")
                .contains("as vendas nao passam pelos limites de compra")
                .contains("nunca impedem a venda de uma posicao")
                .contains("nunca e tratado como seguro e o bot nunca o compra no real")
                .contains("uma posicao que voce ja tem pode ser vendida mesmo com dado ausente");
        assertThat(terms)
                .doesNotContain("antes de cada ordem do real")
                .doesNotContain("lista de tokens permitidos")
                .doesNotContain("nao e operado no real");
    }

    /**
     * review-03 R-13, review-04: the sell-all asked by the person passes only the state checks of {@code
     * RealRiskPolicy.stateAndSource}: REAL on, released, the REAL price source active, and none of the pauses that stop
     * a liquidation (kill switch, divergence, restore). Both sentences list every case; the free SOL is not stopped.
     */
    @Test
    void theSellAllListsEveryCaseThatStopsIt() {
        String terms = normalize(String.join(" ", allText(version("v3").terms())));

        assertThat(terms)
                .contains("essa venda nao e executada enquanto o real estiver desligado ou nao liberado para voce, a"
                        + " fonte de precos do real nao estiver ativa, o kill switch estiver acionado, houver uma"
                        + " divergencia na conferencia com a rede ou uma restauracao estiver em andamento")
                .contains("so o saque do sol livre fica garantido")
                .contains("quando voce mesmo pede para vender tudo, a venda so e recusada quando o real esta desligado"
                        + " ou nao liberado para voce, quando a fonte de precos do real nao esta ativa, ou quando ha o"
                        + " kill switch acionado, uma divergencia na conferencia com a rede ou uma restauracao em"
                        + " andamento");
        assertThat(terms)
                .doesNotContain("o saque libera o sol livre, e voce pode pedir para vender tudo e sacar")
                .doesNotContain("essa venda nao e executada enquanto o kill switch estiver acionado")
                .doesNotContain("so o kill switch, uma divergencia na conferencia com a rede ou uma restauracao em"
                        + " andamento impedem a venda");
    }

    /** review-03 R-15: who pays the network fee depends on who signs; the fee always counts in the REAL result. */
    @Test
    void theNetworkFeeSaysWhichWalletPaysIt() {
        String terms = normalize(String.join(" ", allText(version("v3").terms())));

        assertThat(terms)
                .contains("no modo assinada, a taxa sai da sua carteira principal")
                .contains("quando a carteira do bot executa a ordem, a taxa sai da carteira do bot")
                .contains("a taxa entra no calculo do seu resultado do real");
        assertThat(terms).doesNotContain("com o sol da carteira usada na ordem, e entram no seu resultado");
    }

    /** review-03 R-12, R-14: the policy promises a period only through the table, and names every refusal reason. */
    @Test
    void thePrivacyPolicyOfV3StatesTheRealRetentionHonestly() {
        LegalVersion v3 = version("v3");
        String privacy = normalize(String.join(" ", allText(v3.privacy())));
        String summary = normalize(String.join(" ", v3.privacy().summary()));

        assertThat(privacy)
                .contains("o ledger do real e os depositos nunca sao apagados nem editados")
                .contains("ordem condicional de saida ainda aberta")
                .contains("transacoes ja registradas na rede solana");
        assertThat(privacy + " " + summary)
                .doesNotContain("cada dado fica guardado pelo prazo da tabela")
                .doesNotContain("cada dado tem um prazo de guarda");
        assertThat(summary).contains("o ledger do real nunca e apagado");
    }

    /**
     * review-04: the anonymization clears the bot wallet data in the api only; the TxSigner keeps, with no deletion, the
     * policy of the wallet and every signing record (wallet_policy, policy_change, signing_record of its migration
     * 0001), as proof of what was signed. The policy and the retention table say so, never that everything is deleted.
     */
    @Test
    void theSigningServiceRecordsAreStatedAsKeptAndNeverAsDeleted() {
        LegalVersion v3 = version("v3");
        String privacy = normalize(String.join(" ", allText(v3.privacy())));
        String walletRow = normalize(v3.retention().stream()
                .filter(row -> row.dataItem().startsWith("Carteira do bot"))
                .findFirst()
                .orElseThrow()
                .endAction());

        for (String text : List.of(privacy, walletRow)) {
            assertThat(text)
                    .contains("o servico de assinatura guarda, sem exclusao, a politica da carteira do bot e o registro"
                            + " de cada assinatura")
                    .contains("como prova do que foi assinado");
        }
        assertThat(walletRow).startsWith("anonimizar: na api,");
        assertThat(privacy)
                .doesNotContain("a carteira principal e as mensagens de consentimento ligadas a ela sao apagadas, as"
                        + " liberacoes")
                .doesNotContain("os consentimentos e a carteira principal ligados a ela sao apagados");
        assertThat(walletRow)
                .doesNotContain("anonimizar: a carteira do bot e encerrada, e o endereco, a carteira principal e as"
                        + " mensagens de consentimento sao apagados");
    }

    @Test
    void thePrivacyPolicyOfV3SaysWhichRealDataIsProcessedAndWhy() {
        String privacy = normalize(String.join(" ", allText(version("v3").privacy())));

        assertThat(privacy)
                .contains("endereco da carteira do bot")
                .contains("transacoes")
                .contains("ledger")
                .contains("kms");
    }

    @Test
    void noFileOfV3KeepsAPaperOnlyClaim() throws IOException {
        List<String> found = new ArrayList<>();
        for (String name : TEXT_FILES) {
            String text = normalize(raw("v3/" + name));
            for (String claim : PAPER_ONLY_CLAIMS) {
                if (text.contains(claim)) {
                    found.add(name + ": " + claim);
                }
            }
        }

        assertThat(found).isEmpty();
    }

    @Test
    void v3IsTheOnlyVersionMarkedCurrentAndV2IsNoLongerMarked() {
        List<LegalVersion> versions = realVersions();

        assertThat(versions).extracting(LegalVersion::version).contains("v1", "v2", "v3");
        assertThat(versions.stream().filter(LegalVersion::markedCurrent))
                .extracting(LegalVersion::version)
                .containsExactly("v3");
        assertThat(version("v2").markedCurrent()).isFalse();
        assertThat(version("v3").legacy()).isFalse();
        assertThat(version("v3").effectiveAt()).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
    }

    /**
     * The owner authorized the activation of v3 on 2026-11-20: its review fields are filled (generic, internal only) and
     * it is effective from 2026-11-20T00:00:00Z. With the clock on that day the real catalog makes v3 the effective
     * current version, newest in the history above v1; the draft v2 is ignored and never listed.
     */
    @Test
    void fromItsEffectiveDateV3IsTheEffectiveCurrentVersionOfTheRealCatalog() {
        LegalVersion v3 = version("v3");
        assertThat(v3.reviewed()).isTrue();
        assertThat(v3.legalReviewedAt()).isEqualTo(java.time.LocalDate.parse("2026-11-20"));
        assertThat(v3.legalReviewedBy()).isEqualTo("Assessoria jurídica do projeto");

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.logger();
        logger.addAppender(logs);
        try {
            TermsCatalog catalog = catalogAt("2026-11-20T12:00:00Z");

            assertThat(catalog.current().version()).isEqualTo("v3");
            assertThat(catalog.active().history())
                    .extracting(LegalVersion::version)
                    .containsExactly("v3", "v1");
            assertThat(catalog.find("v2"))
                    .as("v2 never activates: it is a never-published draft")
                    .isEmpty();
            assertThat(warnings(logs))
                    .contains("Legal version v2 ignored: not legally reviewed")
                    .noneMatch(line -> line.contains("v3"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    /** Before 2026-11-20 (the fixed clock of the integration tests is 2026-10-28) v3 is future and v1 stays effective. */
    @Test
    void beforeItsEffectiveDateV3IsFutureAndV1StaysEffective() {
        TermsCatalog catalog = catalogAt("2026-11-19T23:59:59Z");

        assertThat(catalog.current().version()).isEqualTo("v1");
        assertThat(catalog.find("v3")).isEmpty();
        assertThat(catalogAt("2026-10-28T12:00:00Z").current().version()).isEqualTo("v1");
    }

    // ----- helpers -----

    private static TermsCatalog catalogAt(String instant) {
        return new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties(CATALOG),
                LegalSettings.defaults(),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static List<LegalVersion> realVersions() {
        return LegalCatalogReader.read(new PathMatchingResourcePatternResolver(), CATALOG).stream()
                .map(LoadedVersion::legal)
                .toList();
    }

    private static LegalVersion version(String name) {
        return realVersions().stream()
                .filter(item -> item.version().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the real catalog has no " + name));
    }

    private static List<String> allText(LegalText text) {
        List<String> all = new ArrayList<>(text.summary());
        for (LegalSection section : text.sections()) {
            all.add(section.title());
            all.addAll(section.paragraphs());
        }
        return all;
    }

    private static String raw(String path) throws IOException {
        Resource resource = new PathMatchingResourcePatternResolver().getResource(CATALOG + path);
        assertThat(resource.exists()).as(path).isTrue();
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    /** The logger of the catalog, where the warning of an ignored version is written. */
    private static final class LoggerFactory {

        private LoggerFactory() {}

        static org.slf4j.Logger logger() {
            return org.slf4j.LoggerFactory.getLogger(TermsCatalog.class);
        }
    }
}
