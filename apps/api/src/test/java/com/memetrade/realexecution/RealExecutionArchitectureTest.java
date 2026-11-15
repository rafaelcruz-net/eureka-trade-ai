package com.memetrade.realexecution;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.realexecution.domain.ImpureDomainFixture;
import com.memetrade.realexecution.infra.NetworkLeakFixture;
import com.memetrade.realexecution.infra.PaperLeakFixture;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 1 of the U4 of 261113-trading-real-bot (U4-NFR1.4, U4-NFR2.1, U4-NFR8.3, team.md "Fronteiras de módulos"):
 * the new {@code realexecution} module and {@code pnl.real}. Each new rule has a test-only fixture that proves it fails.
 */
@Tag("u4-real-execution")
class RealExecutionArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    /** The daily purge (U4-NFR3.9), the only class of the module that receives the retention pool of U6. */
    static final String PURGE_JOB = "com.memetrade.realexecution.infra.RealExecutionPurgeJob";

    /** U4-NFR1.4: inside {@code realexecution}, only {@code infra.jupiter} reaches the network. */
    static final ArchRule NETWORK_ONLY_IN_JUPITER = noClasses()
            .that()
            .resideInAPackage("com.memetrade.realexecution..")
            .and()
            .resideOutsideOfPackage("com.memetrade.realexecution.infra.jupiter..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.net..",
                    "java.net.http..",
                    "org.springframework.web.client..",
                    "org.springframework.http.client..",
                    "org.springframework.web.reactive.function.client..")
            .because("only the Jupiter adapter calls a provider; the RPC is the adapter of the U1 (U4-NFR1.4)");

    /** U4-NFR2.1: the REAL execution and the P&L REAL never touch the PAPER ledger, balance, orders nor barrier. */
    static final ArchRule NO_PAPER = noClasses()
            .that()
            .resideInAnyPackage("com.memetrade.realexecution..", "com.memetrade.pnl.real..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Paper")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memetrade.ledger.api.LedgerReadModel")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memetrade.riskcontrol.api.OrderGate")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memetrade.riskcontrol.api.CandidateOrder")
            .because("REAL and PAPER never share funds, orders, ledger, positions nor P&L (project.md, U4-NFR2.1)");

    /** U4-NFR8.3, team.md: the module talks to the others only through their api packages. */
    static final ArchRule ONLY_PUBLIC_INTERFACES = noClasses()
            .that()
            .resideInAPackage("com.memetrade.realexecution..")
            .and()
            .doNotHaveFullyQualifiedName(PURGE_JOB)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.memetrade.botwallet.domain..",
                    "com.memetrade.botwallet.application..",
                    "com.memetrade.botwallet.infra..",
                    "com.memetrade.ledger.domain..",
                    "com.memetrade.ledger.application..",
                    "com.memetrade.ledger.infra..",
                    "com.memetrade.riskcontrol.domain..",
                    "com.memetrade.riskcontrol.real..",
                    "com.memetrade.riskcontrol.infra..",
                    "com.memetrade.bots.domain..",
                    "com.memetrade.bots.application..",
                    "com.memetrade.bots.infra..",
                    "com.memetrade.bots.worker..",
                    "com.memetrade.bots.web..",
                    "com.memetrade.marketdata.domain..",
                    "com.memetrade.marketdata.infra..",
                    "com.memetrade.identity.domain..",
                    "com.memetrade.identity.infra..",
                    "com.memetrade.audit.domain..",
                    "com.memetrade.audit.infra..",
                    "com.memetrade.pnl.domain..",
                    "com.memetrade.pnl.application..",
                    "com.memetrade.pnl.infra..")
            .because("a module talks to another only through its public interface (team.md)");

    /**
     * U4-NFR3.9: the purge is the one named exception: besides the public interfaces it receives only the retention pool
     * of U6 ({@code riskcontrol.infra.RiskRetentionDataSource}), as the purge of the U3 does.
     */
    static final ArchRule THE_PURGE_TAKES_ONLY_THE_RETENTION_POOL = noClasses()
            .that()
            .haveFullyQualifiedName(PURGE_JOB)
            .should()
            .dependOnClassesThat(com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage(
                            "com.memetrade.riskcontrol.domain..",
                            "com.memetrade.riskcontrol.real..",
                            "com.memetrade.riskcontrol.infra..",
                            "com.memetrade.botwallet..",
                            "com.memetrade.ledger..",
                            "com.memetrade.bots..",
                            "com.memetrade.audit.infra..")
                    .and(com.tngtech.archunit.base.DescribedPredicate.not(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo(
                                    com.memetrade.riskcontrol.infra.RiskRetentionDataSource.class))))
            .because("each Unit purges through the retention pool of U6 and reads nothing else of another module");

    static final ArchRule NOBODY_READS_THE_INTERNALS = noClasses()
            .that()
            .resideOutsideOfPackage("com.memetrade.realexecution..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.memetrade.realexecution.domain..",
                    "com.memetrade.realexecution.infra..",
                    "com.memetrade.realexecution.web..")
            .because("the other modules use M6 (realexecution.api) only");

    /** The domain (state machine, quote math, message builder) depends on no infrastructure. */
    static final ArchRule PURE_DOMAIN = noClasses()
            .that()
            .resideInAnyPackage("com.memetrade.realexecution.domain..", "com.memetrade.pnl.real..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..infra..", "..web..", "org.springframework..", "jakarta.servlet..", "java.sql..", "java.net..")
            .because("the state machine, the quote math, the message and the P&L are pure (team.md)");

    @Test
    void onlyTheJupiterAdapterUsesTheNetwork() {
        NETWORK_ONLY_IN_JUPITER.check(PRODUCTION);
    }

    @Test
    void theNetworkRuleFailsForARealExecutionClassOutsideJupiter() {
        JavaClasses sample = new ClassFileImporter().importClasses(NetworkLeakFixture.class);

        assertThatThrownBy(() -> NETWORK_ONLY_IN_JUPITER.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NetworkLeakFixture");
    }

    @Test
    void theRealExecutionNeverTouchesPaper() {
        NO_PAPER.check(PRODUCTION);
    }

    @Test
    void thePaperRuleFailsForARealExecutionClassUsingThePaperLedger() {
        JavaClasses sample = new ClassFileImporter().importClasses(PaperLeakFixture.class);

        assertThatThrownBy(() -> NO_PAPER.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PaperLeakFixture");
    }

    @Test
    void theModuleUsesOnlyThePublicInterfacesOfTheOthers() {
        ONLY_PUBLIC_INTERFACES.check(PRODUCTION);
        THE_PURGE_TAKES_ONLY_THE_RETENTION_POOL.check(PRODUCTION);
        NOBODY_READS_THE_INTERNALS.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void theBoundaryRuleFailsForAClassReadingAnotherModuleInternals() {
        JavaClasses sample = new ClassFileImporter().importClasses(PaperLeakFixture.class);

        assertThatThrownBy(() -> ONLY_PUBLIC_INTERFACES.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PaperLeakFixture");
    }

    @Test
    void theDomainIsPure() {
        PURE_DOMAIN.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void thePurityRuleFailsForADomainClassUsingJdbc() {
        JavaClasses sample = new ClassFileImporter().importClasses(ImpureDomainFixture.class);

        assertThatThrownBy(() -> PURE_DOMAIN.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ImpureDomainFixture");
    }

    @Test
    void theJupiterPackageIsOutsideTheNetworkRule() {
        assertThatCode(() -> NETWORK_ONLY_IN_JUPITER
                        .allowEmptyShould(true)
                        .check(new ClassFileImporter().importPackages("com.memetrade.realexecution.infra.jupiter")))
                .doesNotThrowAnyException();
    }

    /** project.md: lamports, base units, prices and P&L never use floating point. */
    @Test
    void noFieldNorReturnIsFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("com.memetrade.realexecution..", "com.memetrade.pnl.real..")
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
                .resideInAnyPackage("com.memetrade.realexecution..", "com.memetrade.pnl.real..")
                .should()
                .notHaveRawReturnType(double.class)
                .andShould()
                .notHaveRawReturnType(float.class)
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }
}
