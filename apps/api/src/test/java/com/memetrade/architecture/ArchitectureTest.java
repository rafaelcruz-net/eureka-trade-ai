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

    private static final List<String> MODULES = List.of("identity", "marketdata", "scanner", "audit");

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
                        "com.memetrade.audit..")
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

    @Test
    void theMarketModuleNeverUsesTheNetwork() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.marketdata..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.net..", "java.net.http..")
                .because("the simulated source is local; real adapters arrive in U4 (C-06)")
                .check(PRODUCTION);
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
