package com.memetrade.realexits;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.realexecution.exitsfixture.RealExitsImportFixture;
import com.memetrade.realexits.archfixture.ExitsFixtures;
import com.memetrade.realexits.domain.ImpureExitsDomainFixture;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 1 of the U7 of 261113-trading-real-bot (U7-NFR1.8, U7-NFR2.1, U7-NFR7.1, logical-components.md "ArchUnit" and
 * "Fronteiras", infrastructure-specification.md "Caminho simulado"): the new module {@code realexits}. Each rule has a
 * test-only fixture that proves it fails.
 */
@Tag("u7-exits-and-fiscal")
class RealExitsArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    /** The daily purge (U7-NFR3.7), the one class of the module that receives the retention pool of the U6. */
    static final String PURGE_JOB = "com.memetrade.realexits.infra.RealExitsPurgeJob";

    static final String SIMULATED = "com.memetrade.realexits.infra.SimulatedTriggerAdapter";

    /**
     * logical-components.md "ArchUnit": the public interfaces the module may use. Besides the six of the design, the
     * identity (the person of the token, the idempotency and the check of the consent, M9) and the audit trail (the
     * outbox, C9) are public interfaces every Unit uses.
     */
    static final String[] ALLOWED = {
        "com.memetrade.realexits..",
        "com.memetrade.botwallet.api..",
        "com.memetrade.ledger.api..",
        "com.memetrade.riskcontrol.api..",
        "com.memetrade.realexecution.api..",
        "com.memetrade.marketdata.api..",
        "com.memetrade.shared..",
        "com.memetrade.identity.api..",
        "com.memetrade.audit.api.."
    };

    static final ArchRule ONLY_THE_ALLOWED_INTERFACES = noClasses()
            .that()
            .resideInAPackage("com.memetrade.realexits..")
            .and()
            .doNotHaveFullyQualifiedName(PURGE_JOB)
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.memetrade..")
                    .and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(ALLOWED))))
            .because("realexits talks to the other modules only through their public interfaces (team.md)");

    static final ArchRule THE_PURGE_TAKES_ONLY_THE_RETENTION_POOL = noClasses()
            .that()
            .haveFullyQualifiedName(PURGE_JOB)
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.memetrade..")
                    .and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(ALLOWED)))
                    .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(
                            com.memetrade.riskcontrol.infra.RiskRetentionDataSource.class))))
            .because("each Unit purges through the retention pool of U6 and reads nothing else of another module");

    /** Q2: no cycle; the U4 and the U5 see the conditional exits only through their own extension interfaces. */
    static final ArchRule NOBODY_IMPORTS_REALEXITS = noClasses()
            .that()
            .resideInAnyPackage("com.memetrade.realexecution..", "com.memetrade.botwallet..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.memetrade.realexits..")
            .because("realexecution and botwallet declare the extension interfaces that realexits implements");

    /** U7-NFR1.8: inside {@code realexits}, only {@code infra.jupiter} reaches the network. */
    static final ArchRule NETWORK_ONLY_IN_JUPITER = noClasses()
            .that()
            .resideInAPackage("com.memetrade.realexits..")
            .and()
            .resideOutsideOfPackage("com.memetrade.realexits.infra.jupiter..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.net..",
                    "java.net.http..",
                    "org.springframework.web.client..",
                    "org.springframework.http.client..",
                    "org.springframework.web.reactive.function.client..")
            .because("only the Jupiter adapter calls a provider; the RPC is the adapter of the U1 (U7-NFR1.8)");

    /** U7-NFR1.8: no signature outside the TxSigner; the module never asks the bot wallet to sign. */
    static final ArchRule NEVER_SIGNS = noClasses()
            .that()
            .resideInAPackage("com.memetrade.realexits..")
            .should()
            .callMethodWhere(target(nameMatching("sign[A-Z].*")))
            .because("the bot wallet signs only in the TxSigner (project.md)");

    /** U7-NFR2.1: PAPER never enters the conditionals nor the export. */
    static final ArchRule NO_PAPER = noClasses()
            .that()
            .resideInAPackage("com.memetrade.realexits..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Paper")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memetrade.ledger.api.LedgerReadModel")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memetrade.riskcontrol.api.OrderGate")
            .because("REAL and PAPER never share funds, orders, ledger nor P&L (project.md, U7-NFR2.1)");

    /** infrastructure-specification.md: no production class references the simulated adapter. */
    static final ArchRule THE_SIMULATED_ADAPTER_IS_NEVER_REFERENCED = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(SIMULATED)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(SIMULATED)
            .because("the simulated adapter exists only with the test profile (BR0.3)");

    /** The domain (state machine, validation, port) depends on no infrastructure. */
    static final ArchRule PURE_DOMAIN = noClasses()
            .that()
            .resideInAPackage("com.memetrade.realexits.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..infra..", "..web..", "org.springframework..", "jakarta.servlet..", "java.sql..", "java.net..")
            .because("the state machine, the validation of the prices and the port are pure (team.md)");

    @Test
    void theModuleUsesOnlyThePublicInterfacesOfTheOthers() {
        ONLY_THE_ALLOWED_INTERFACES.check(PRODUCTION);
        THE_PURGE_TAKES_ONLY_THE_RETENTION_POOL.check(PRODUCTION);
    }

    @Test
    void theBoundaryRuleFailsForAClassReadingTheInternalsOfTheBotWallet() {
        JavaClasses sample = new ClassFileImporter().importClasses(ExitsFixtures.ExitsInternalsFixture.class);

        assertThatThrownBy(() -> ONLY_THE_ALLOWED_INTERFACES.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ExitsInternalsFixture");
    }

    @Test
    void neitherTheExecutionNorTheBotWalletImportTheExits() {
        NOBODY_IMPORTS_REALEXITS.check(PRODUCTION);
    }

    @Test
    void theImportRuleFailsForAnExecutionClassUsingTheExits() {
        JavaClasses sample = new ClassFileImporter().importClasses(RealExitsImportFixture.class);

        assertThatThrownBy(() -> NOBODY_IMPORTS_REALEXITS.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RealExitsImportFixture");
    }

    @Test
    void onlyTheJupiterPackageReachesTheNetwork() {
        NETWORK_ONLY_IN_JUPITER.check(PRODUCTION);
    }

    @Test
    void theNetworkRuleFailsForAClassWithItsOwnClient() {
        JavaClasses sample = new ClassFileImporter().importClasses(ExitsFixtures.ExitsNetworkFixture.class);

        assertThatThrownBy(() -> NETWORK_ONLY_IN_JUPITER.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ExitsNetworkFixture");
    }

    @Test
    void theModuleNeverSigns() {
        NEVER_SIGNS.check(PRODUCTION);
    }

    @Test
    void theSigningRuleFailsForAClassAskingASwapSignature() {
        JavaClasses sample = new ClassFileImporter().importClasses(ExitsFixtures.ExitsSigningFixture.class);

        assertThatThrownBy(() -> NEVER_SIGNS.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ExitsSigningFixture");
    }

    @Test
    void theModuleNeverTouchesPaper() {
        NO_PAPER.check(PRODUCTION);
    }

    @Test
    void thePaperRuleFailsForAClassUsingThePaperExecution() {
        JavaClasses sample = new ClassFileImporter().importClasses(ExitsFixtures.ExitsPaperFixture.class);

        assertThatThrownBy(() -> NO_PAPER.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ExitsPaperFixture");
    }

    @Test
    void noProductionClassReferencesTheSimulatedAdapter() {
        THE_SIMULATED_ADAPTER_IS_NEVER_REFERENCED.check(PRODUCTION);
    }

    @Test
    void theSimulatedRuleFailsForAClassThatReferencesIt() {
        JavaClasses sample = new ClassFileImporter().importClasses(ExitsFixtures.SimulatedReferenceFixture.class);

        assertThatThrownBy(() -> THE_SIMULATED_ADAPTER_IS_NEVER_REFERENCED.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SimulatedReferenceFixture");
    }

    @Test
    void theDomainIsPure() {
        PURE_DOMAIN.check(PRODUCTION);
    }

    @Test
    void thePurityRuleFailsForADomainClassUsingJdbc() {
        JavaClasses sample = new ClassFileImporter().importClasses(ImpureExitsDomainFixture.class);

        assertThatThrownBy(() -> PURE_DOMAIN.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ImpureExitsDomainFixture");
    }

    /** U7-NFR7.1, project.md: prices, quantities and lamports never use floating point. */
    @Test
    void noFieldNorReturnIsFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAPackage("com.memetrade.realexits..")
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .allowEmptyShould(true)
                .check(PRODUCTION);
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("com.memetrade.realexits..")
                .should()
                .notHaveRawReturnType(double.class)
                .andShould()
                .notHaveRawReturnType(float.class)
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }
}
