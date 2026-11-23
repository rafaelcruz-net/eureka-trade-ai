package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Module boundaries and production-code bans of team.md / project.md, checked on the compiled main classes. */
@Tag("u1")
class ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final List<String> MODULES = List.of("identity", "marketdata", "scanner", "audit", "riskassessment");

    @Test
    void aModuleTalksToAnotherOnlyThroughItsPublicApiPackage() {
        for (String module : MODULES) {
            for (String other : MODULES) {
                if (module.equals(other)) {
                    continue;
                }
                ArchRule rule = noClasses()
                        .that()
                        .resideInAPackage("com.memetrade." + module + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "com.memetrade." + other + ".domain..",
                                "com.memetrade." + other + ".infra..",
                                "com.memetrade." + other + ".web..")
                        .because("modules never read another module's tables, repositories or internals (team.md)");
                rule.check(PRODUCTION);
            }
        }
    }

    @Test
    void theDomainDependsNeitherOnWebNorOnInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..web..",
                        "..infra..",
                        "jakarta.servlet..",
                        "org.springframework.web..",
                        "org.springframework.jdbc..")
                .check(PRODUCTION);
    }

    @Test
    void sharedDoesNotDependOnAnyBusinessModule() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.shared..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.identity..",
                        "com.memetrade.marketdata..",
                        "com.memetrade.scanner..",
                        "com.memetrade.audit..",
                        "com.memetrade.riskassessment..")
                .check(PRODUCTION);
    }

    @Test
    void productionCodeNeverGeneratesOrImportsKeysOrMnemonics() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.KeyPairGenerator")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.PrivateKey")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.spec.PKCS8EncodedKeySpec")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleNameContaining("Mnemonic")
                .orShould()
                .dependOnClassesThat()
                .resideInAnyPackage("org.p2p.solanaj..", "org.bitcoinj..", "org.sol4k..", "software.sava..")
                .because("project.md forbids key generation/import and mnemonic APIs in production code")
                .check(PRODUCTION);
    }

    @Test
    void theKeyGenerationRuleDetectsAViolation() {
        JavaClasses testWallet = new ClassFileImporter().importClasses(com.memetrade.testsupport.TestWallet.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> noClasses()
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("java.security.KeyPairGenerator")
                        .check(testWallet))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("KeyPairGenerator");
    }

    @Test
    void timeComesOnlyFromTheInjectedClock() {
        noClasses()
                .that()
                .doNotHaveSimpleName("ClockConfig")
                .should()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(LocalDate.class, "now")
                .orShould()
                .callMethod(OffsetDateTime.class, "now")
                .orShould()
                .callMethod(ZonedDateTime.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(Clock.class, "systemUTC")
                .orShould()
                .callMethod(Clock.class, "systemDefaultZone")
                .because("the domain uses the injectable Clock (team.md determinism)")
                .check(PRODUCTION);
    }

    @Test
    void marketAndScannerValuesNeverUseFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("com.memetrade.marketdata..", "com.memetrade.scanner..")
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .check(PRODUCTION);
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("com.memetrade.marketdata.api..", "com.memetrade.scanner..")
                .and()
                .doNotHaveName("statusValue")
                .should()
                .notHaveRawReturnType(double.class)
                .andShould()
                .notHaveRawReturnType(float.class)
                .check(PRODUCTION);
    }

    /**
     * 261121-paper-real-unico U2 (project.md "NEVER ponto flutuante para valores monetários", Etapa 23): the REAL ledger
     * API, the REAL calculation and the P&L application keep lamports, base units and prices in exact types only.
     */
    @Test
    void theRealLedgerAndThePnlNeverUseFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.ledger.api..", "com.memetrade.pnl.real..", "com.memetrade.pnl.application..")
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .check(PRODUCTION);
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("com.memetrade.ledger.api..", "com.memetrade.pnl.real..")
                .should()
                .notHaveRawReturnType(double.class)
                .andShould()
                .notHaveRawReturnType(float.class)
                .andShould()
                .notHaveRawReturnType(Double.class)
                .andShould()
                .notHaveRawReturnType(Float.class)
                .check(PRODUCTION);
    }

    /**
     * 261113-trading-real-bot U2 (NFR1.4, security-design.md "Isolamento de rede"): only the CoinGecko adapter package
     * may reach the network; the rest of the market module (and the simulated sources) never does.
     */
    static final ArchRule MARKET_NETWORK_RULE = noClasses()
            .that()
            .resideInAPackage("com.memetrade.marketdata..")
            .and()
            .resideOutsideOfPackage("com.memetrade.marketdata.infra.coingecko..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.net..",
                    "java.net.http..",
                    "org.springframework.web.client..",
                    "org.springframework.http.client..")
            .because("only the real adapter in marketdata.infra.coingecko calls the provider (U2 NFR1.4)");

    @Test
    void theMarketModuleNeverUsesTheNetwork() {
        MARKET_NETWORK_RULE.check(PRODUCTION);
    }

    @Test
    void theNetworkRuleFailsForAMarketClassOutsideTheCoinGeckoPackage() {
        JavaClasses sample =
                new ClassFileImporter().importClasses(com.memetrade.marketdata.infra.NetworkUsingFixture.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MARKET_NETWORK_RULE.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NetworkUsingFixture");
    }

    @Test
    void theNetworkRuleAllowsTheCoinGeckoPackage() {
        JavaClasses adapter =
                new ClassFileImporter().importClasses(com.memetrade.marketdata.infra.coingecko.CoinGeckoHttp.class);

        // The adapter package is outside the "that" clause, so nothing is left to check: no violation, empty should.
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> MARKET_NETWORK_RULE.allowEmptyShould(true).check(adapter))
                .doesNotThrowAnyException();
    }

    @Test
    void theBuildBansTheSolanaSdkOutsideTestScope() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom)
                .contains("<bannedDependencies>")
                .contains("com.mmorrell:solanaj:*:*:compile")
                .contains("com.mmorrell:solanaj:*:*:runtime");
        assertThat(pom.substring(pom.indexOf("<artifactId>solanaj</artifactId>")))
                .as("the SDK dependency itself is test scope")
                .startsWith("<artifactId>solanaj</artifactId>")
                .contains("<scope>test</scope>");
    }
}
