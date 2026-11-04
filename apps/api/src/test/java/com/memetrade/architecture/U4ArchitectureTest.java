package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Rules of the U4 modules (security-design.md, NFR4.1, NFR5.1, NFR6.9) on the compiled production classes, plus a
 * source scan for the tables of U4.
 */
@Tag("u4")
class U4ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final String[] U4 = {
        "com.memetrade.marketdata..", "com.memetrade.scanner..", "com.memetrade.riskassessment.."
    };

    static final ArchRule NO_FLOATING_POINT = fields().that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(U4)
            .should()
            .notHaveRawType(double.class)
            .andShould()
            .notHaveRawType(float.class)
            .andShould()
            .notHaveRawType(Double.class)
            .andShould()
            .notHaveRawType(Float.class)
            .because("market values, weights, coverage and indicators are exact decimals (NFR4.1, project.md)");

    static final ArchRule NO_OPERATION_PATH = noClasses()
            .that()
            .resideInAnyPackage(U4)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.memetrade.bots..",
                    "com.memetrade.orders..",
                    "com.memetrade.ledger..",
                    "com.memetrade.shared.wallet..",
                    "com.memetrade.identity.domain.signature..",
                    "java.security.Signature..")
            .because("discovery and assessment never sign, order or move funds (NFR6.9)");

    static final ArchRule NO_ADMIN_PRINCIPAL = noClasses()
            .that()
            .resideInAnyPackage(U4)
            .should()
            .callConstructor(Principal.class, java.util.UUID.class, java.util.UUID.class, Role.class)
            .orShould()
            .accessField(Role.class, "ADMIN")
            .because("the weight administration checks the role through AdminAccess only (NFR6.4)");

    @Test
    void theU4PackagesNeverUseFloatingPoint() {
        NO_FLOATING_POINT.check(PRODUCTION);
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("com.memetrade.riskassessment..", "com.memetrade.scanner..")
                .should()
                .notHaveRawReturnType(double.class)
                .andShould()
                .notHaveRawReturnType(float.class)
                .check(PRODUCTION);
    }

    @Test
    void theScannerReadsMarketDataAndRiskOnlyThroughTheirPublicInterfaces() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.scanner..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.marketdata.domain..",
                        "com.memetrade.marketdata.infra..",
                        "com.memetrade.riskassessment.domain..",
                        "com.memetrade.riskassessment.infra..")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.riskassessment..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.memetrade.marketdata.domain..", "com.memetrade.marketdata.infra..")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.marketdata..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.memetrade.riskassessment..", "com.memetrade.scanner..")
                .because("MarketData is the provider: it never calls back its consumers")
                .check(PRODUCTION);
    }

    @Test
    void noU4ModuleDependsOnOrdersLedgerWalletOrSignatures() {
        NO_OPERATION_PATH.check(PRODUCTION);
    }

    @Test
    void noU4ProductionCodeCreatesAnAdministratorPrincipal() {
        NO_ADMIN_PRINCIPAL.check(PRODUCTION);
    }

    @Test
    void theRulesDetectAViolation() {
        JavaClasses testOnly = new ClassFileImporter().importClasses(com.memetrade.testsupport.AdminSessions.class);

        assertThatThrownBy(() ->
                        noClasses().should().accessField(Role.class, "ADMIN").check(testOnly))
                .isInstanceOf(AssertionError.class);
    }

    /** Only MarketData and RiskAssessment name the U4 tables (the list query joins risk_assessment by Q3). */
    @Test
    void noOtherModuleReadsTheU4Tables() throws IOException {
        List<String> tables = List.of(
                "observation_sample", "risk_weight_set", "risk_assessment", "source_status_mark", "market_observation");
        Path main = Path.of("src", "main", "java", "com", "memetrade");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(main)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(main.resolve("marketdata")))
                    .filter(path -> !path.startsWith(main.resolve("riskassessment")))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            return tables.stream()
                                    .anyMatch(table -> source.contains(" " + table + " ")
                                            || source.contains(" " + table + "\n")
                                            || source.contains("from " + table)
                                            || source.contains("into " + table)
                                            || source.contains("update " + table));
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }
}
