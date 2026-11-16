package com.memetrade.botwallet;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.opsfixture.DivergencePaperFixture;
import com.memetrade.botwallet.opsfixture.ReconciliationNetworkLeakFixture;
import com.memetrade.botwallet.opsfixture.ReleaseSigningFixture;
import com.memetrade.botwallet.opsfixture.RiskDependencyFixture;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 1 of the U5 of 261113-trading-real-bot (U5-NFR1.2, U5-NFR2.1, U5-NFR8.1, logical-components.md iteration 2):
 * the REAL operations live in {@code botwallet} and only read the network through the RPC of the U1 and the GitHub
 * adapter; they never ask a swap or withdrawal signature; the reconciliation never reads PAPER; and {@code botwallet}
 * never depends on {@code riskcontrol}, which already reads the M1 (the revocation takes the lock of the person in the
 * {@code RealAdminController}). Each rule has a test-only fixture that proves it fails.
 */
@Tag("u5-real-operations")
class RealOpsArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    /** The classes of the REAL operations of the U5 (production and the fixtures of this test). */
    static final String OPERATIONS = "com\\.memetrade\\.(botwallet\\.[a-z.]*\\.(Release|Gate|Reconciliation|Divergence"
            + "|RealKillSwitch|KillSwitchCancel|TxSignerCommand|PendingCommand|Restore|RealOps|RealAdministration"
            + "|BotWalletAnonymization|GlobalPauses|WithdrawalLimit|Compensation|CiStatus|CiResult|GitHubCiStatus"
            + "|ConfiguredCiStatus)[A-Za-z0-9$]*|administration\\.web\\.RealAdmin[A-Za-z0-9$]*)";

    /** The retention pool of U6, the one named exception of the purge (as in the U3 and the U4). */
    static final String PURGE_JOB = "com.memetrade.botwallet.infra.RealOpsPurgeJob";

    /** U5-NFR8.1: inside {@code botwallet}, only the GitHub adapter, the RPC and the TxSigner client reach the network. */
    static final ArchRule NETWORK_ONLY_IN_GITHUB_AND_RPC = noClasses()
            .that()
            .resideInAPackage("com.memetrade.botwallet..")
            .and()
            .resideOutsideOfPackages("com.memetrade.botwallet.infra.github..", "com.memetrade.botwallet.infra.chain..")
            .and()
            .doNotHaveFullyQualifiedName("com.memetrade.botwallet.infra.HttpTxSignerClient")
            .and()
            .doNotHaveFullyQualifiedName("com.memetrade.botwallet.infra.BotWalletConfig")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.net.http..",
                    "org.springframework.web.client..",
                    "org.springframework.http.client..",
                    "org.springframework.web.reactive.function.client..")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.HttpURLConnection")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.Socket")
            .because("the U5 reads the network only through the RPC of the U1 and the CI adapter (U5-NFR8.1)");

    /** U5-NFR1.2: the operations never ask a signature; only LOWER_CAP and DEACTIVATE (CLOSE) reach the TxSigner. */
    static final ArchRule OPERATIONS_NEVER_SIGN = noClasses()
            .that()
            .haveNameMatching(OPERATIONS)
            .should()
            .callMethodWhere(target(name("signSwap")))
            .orShould()
            .callMethodWhere(target(name("signWithdrawal")))
            .because("the REAL operations only read the network; they never sign (U5-NFR1.2)");

    /** U5-NFR2.1: the reconciliation and the divergences compare only the REAL ledger with the bot wallet. */
    static final ArchRule RECONCILIATION_WITHOUT_PAPER = noClasses()
            .that()
            .haveNameMatching("com\\.memetrade\\.botwallet\\..*\\.(Reconciliation|Divergence)[A-Za-z0-9$]*")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Paper")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memetrade.ledger.api.LedgerReadModel")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memetrade.ledger.api.PaperExecution")
            .because("REAL and PAPER never share funds, ledger nor P&L (project.md, U5-NFR2.1)");

    /** logical-components.md iteration 2: no cycle with the risk control, which reads the M1. */
    static final ArchRule BOTWALLET_WITHOUT_RISKCONTROL = noClasses()
            .that()
            .resideInAPackage("com.memetrade.botwallet..")
            .and()
            .doNotHaveFullyQualifiedName(PURGE_JOB)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.memetrade.riskcontrol..")
            .because("the RealAdminController takes the lock of the person (M5) around the M1; botwallet never does");

    /** The purge receives only the retention pool of U6 from the risk control (the named exception of U3 and U4). */
    static final ArchRule THE_PURGE_TAKES_ONLY_THE_RETENTION_POOL = noClasses()
            .that()
            .haveFullyQualifiedName(PURGE_JOB)
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.memetrade.riskcontrol..")
                    .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(
                            com.memetrade.riskcontrol.infra.RiskRetentionDataSource.class))))
            .because("each Unit purges through the retention pool of U6 and reads nothing else of another module");

    @Test
    void onlyTheGitHubAdapterTheRpcAndTheSignerClientUseTheNetwork() {
        NETWORK_ONLY_IN_GITHUB_AND_RPC.check(PRODUCTION);
    }

    @Test
    void theNetworkRuleFailsForAnOperationClassWithItsOwnClient() {
        JavaClasses sample = new ClassFileImporter().importClasses(ReconciliationNetworkLeakFixture.class);

        assertThatThrownBy(() -> NETWORK_ONLY_IN_GITHUB_AND_RPC.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ReconciliationNetworkLeakFixture");
    }

    @Test
    void theOperationsNeverAskASignature() {
        OPERATIONS_NEVER_SIGN.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void theSignatureRuleFailsForAnOperationClassSigningAWithdrawal() {
        JavaClasses sample = new ClassFileImporter().importClasses(ReleaseSigningFixture.class);

        assertThatThrownBy(() -> OPERATIONS_NEVER_SIGN.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ReleaseSigningFixture");
    }

    @Test
    void theReconciliationNeverReadsPaper() {
        RECONCILIATION_WITHOUT_PAPER.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void thePaperRuleFailsForAReconciliationClassReadingThePaperLedger() {
        JavaClasses sample = new ClassFileImporter().importClasses(DivergencePaperFixture.class);

        assertThatThrownBy(() -> RECONCILIATION_WITHOUT_PAPER.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DivergencePaperFixture");
    }

    @Test
    void theBotWalletNeverDependsOnTheRiskControl() {
        BOTWALLET_WITHOUT_RISKCONTROL.check(PRODUCTION);
        THE_PURGE_TAKES_ONLY_THE_RETENTION_POOL.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void theRiskRuleFailsForABotWalletClassUsingTheBarrier() {
        JavaClasses sample = new ClassFileImporter().importClasses(RiskDependencyFixture.class);

        assertThatThrownBy(() -> BOTWALLET_WITHOUT_RISKCONTROL.check(sample))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RiskDependencyFixture");
    }
}
