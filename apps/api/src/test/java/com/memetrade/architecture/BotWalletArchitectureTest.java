package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Step 1 of the U1 of 261113-trading-real-bot (tech-stack-decisions.md, NFR1.3, team.md "Fronteiras de módulos"): the
 * new {@code botwallet} module talks to other modules only through their {@code api} packages, its domain depends on
 * no infrastructure, it never signs, never holds a KMS client and never uses floating point.
 */
class BotWalletArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final String BOTWALLET = "com.memetrade.botwallet..";

    @Test
    void theBotWalletUsesOtherModulesOnlyThroughTheirApi() {
        noClasses()
                .that()
                .resideInAPackage(BOTWALLET)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.ledger.domain..",
                        "com.memetrade.ledger.application..",
                        "com.memetrade.ledger.infra..",
                        "com.memetrade.audit.domain..",
                        "com.memetrade.audit.infra..",
                        "com.memetrade.identity.domain..",
                        "com.memetrade.identity.infra..",
                        "com.memetrade.administration.domain..",
                        "com.memetrade.administration.infra..")
                .because("team.md: a module talks to another only through its public interface or an event")
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    void noOtherModuleReadsTheBotWalletInternals() {
        noClasses()
                .that()
                .resideOutsideOfPackage(BOTWALLET)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.botwallet.domain..",
                        "com.memetrade.botwallet.infra..",
                        "com.memetrade.botwallet.web..")
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    void theBotWalletDomainDependsOnNoInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.botwallet.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..infra..",
                        "..web..",
                        "org.springframework..",
                        "jakarta.servlet..",
                        "java.sql..",
                        "java.net..")
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    void theMonolithNeverSignsNorHoldsAKmsClient() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("software.amazon.awssdk.services.kms..")
                .orShould()
                .callMethod(java.security.Signature.class, "initSign", java.security.PrivateKey.class)
                .because("only the TxSigner signs with the bot wallet key (ADR-010, NFR1.2)")
                .check(PRODUCTION);
    }

    @Test
    void lamportsAreNeverFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAPackage(BOTWALLET)
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .because("lamports are long with overflow checks (NFR10.1, project.md)")
                .allowEmptyShould(true)
                .check(PRODUCTION);
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage(BOTWALLET)
                .should()
                .notHaveRawReturnType(double.class)
                .andShould()
                .notHaveRawReturnType(float.class)
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void realIsOffByDefaultInTheShippedConfiguration() throws Exception {
        Map<String, Object> config = new Yaml().load(Files.readString(Path.of("src/main/resources/application.yml")));
        Map<String, Object> memetrade = (Map<String, Object>) config.get("memetrade");
        Map<String, Object> real = (Map<String, Object>) memetrade.get("real");

        Assertions.assertThat(real.get("enabled"))
                .as("memetrade.real.enabled must ship as false (team.md, BR1.1)")
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    void theBuildBansTheKmsClientInTheMonolith() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        Assertions.assertThat(pom).contains("<exclude>software.amazon.awssdk:kms</exclude>");
    }
}
