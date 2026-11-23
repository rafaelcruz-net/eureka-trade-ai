package com.memetrade.riskcontrol.real;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.riskcontrol.api.RealRiskGate;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 1 of the U3 of 261113-trading-real-bot (logical-components.md "Regras ArchUnit que mudam", U3-NFR8.1,
 * U3-NFR6.2, U3-NFR4.7, U3-NFR2.1): the boundary of the REAL barrier, the callers of the block list and the
 * independence of the withdrawal from the barrier. Each rule has a test with a test-only fixture that proves it fails.
 */
class RealRiskArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final String REAL = "com.memetrade.riskcontrol.real..";

    /**
     * U3-NFR8.1: the REAL barrier depends only on M1 ({@code botwallet.api}), the REAL reads of M2 ({@code ledger.api}
     * types {@code Real*}), M4 ({@code marketdata.api}), the indicator ({@code riskassessment.api}), the audit outbox
     * ({@code audit.api}), {@code shared}, its own module api and itself; never the PAPER barrier (the retention pool of
     * the purge is the one shared exception, logical-components.md) and never an internal class of another module.
     */
    static final ArchRule REAL_BOUNDARY = noClasses()
            .that()
            .resideInAPackage(REAL)
            .should()
            .dependOnClassesThat(outsideTheRealBoundary())
            .because("U3-NFR8.1: the REAL barrier talks to other modules only through their public interfaces");

    /** U3-NFR6.2: only the administrator area (U5) changes the block list; the barrier itself implements it. */
    static final ArchRule BLOCK_LIST_CALLERS = noClasses()
            .that()
            .resideOutsideOfPackages("com.memetrade.administration..", "com.memetrade.riskcontrol..")
            .should()
            .callMethodWhere(new DescribedPredicate<>("a call of RealRiskGate.blockToken or unblockToken") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                    return call.getTargetOwner().isAssignableTo(RealRiskGate.class)
                            && List.of("blockToken", "unblockToken")
                                    .contains(call.getTarget().getName());
                }
            })
            .because("U3-NFR6.2: the block list changes only from the administrator area")
            .allowEmptyShould(true);

    /** U3-NFR4.7: the withdrawal of the BotWallet never passes through the barrier. */
    static final ArchRule BOTWALLET_WITHOUT_RISKCONTROL = noClasses()
            .that()
            .resideInAPackage("com.memetrade.botwallet..")
            // 261113-trading-real-bot U5 (U5-NFR3.9): the one named exception, the purge of the operations of the REAL,
            // which receives only the retention pool of the module (RealOpsArchitectureTest checks it reaches nothing
            // else of riskcontrol); no withdrawal path goes through it.
            .and()
            .doNotHaveFullyQualifiedName("com.memetrade.botwallet.infra.RealOpsPurgeJob")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.memetrade.riskcontrol..")
            .because("U3-NFR4.7: the withdrawal is never affected by the barrier")
            .allowEmptyShould(true);

    /** The pure policy and its inputs depend on no infrastructure, Spring nor JDBC (Q4 of the NFR Design). */
    static final ArchRule PURE_DOMAIN = noClasses()
            .that()
            .resideInAPackage("com.memetrade.riskcontrol.real.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.memetrade.riskcontrol.real.infra..",
                    "org.springframework..",
                    "java.sql..",
                    "javax.sql..",
                    "java.net..")
            .because("the policy is pure: no I/O and no clock of its own");

    @Test
    void theRealBarrierDependsOnlyOnTheAllowedInterfaces() {
        REAL_BOUNDARY.check(PRODUCTION);
    }

    @Test
    void theBoundaryRuleFailsForAPaperOrInternalDependency() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(com.memetrade.riskcontrol.real.infra.BoundaryViolationFixture.class);

        assertThatThrownBy(() -> REAL_BOUNDARY.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BoundaryViolationFixture")
                .hasMessageContaining("com.memetrade.riskcontrol.domain.LimitChecks")
                .hasMessageContaining("com.memetrade.botwallet.domain.PauseReason")
                .hasMessageContaining("com.memetrade.ledger.api.PaperExecution");
    }

    @Test
    void onlyTheAdministrationChangesTheBlockList() {
        BLOCK_LIST_CALLERS.check(PRODUCTION);
    }

    @Test
    void theBlockListRuleFailsForACallerOutsideTheAdministration() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(com.memetrade.bots.infra.BlockListCallerFixture.class, RealRiskGate.class);

        assertThatThrownBy(() -> BLOCK_LIST_CALLERS.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BlockListCallerFixture")
                .hasMessageContaining("blockToken");
    }

    @Test
    void theBotWalletNeverDependsOnTheBarrier() {
        BOTWALLET_WITHOUT_RISKCONTROL.check(PRODUCTION);
    }

    @Test
    void theBotWalletRuleFailsForAWithdrawalThatCallsTheBarrier() {
        JavaClasses fixture =
                new ClassFileImporter().importClasses(com.memetrade.botwallet.infra.BarrierUsingFixture.class);

        assertThatThrownBy(() -> BOTWALLET_WITHOUT_RISKCONTROL.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BarrierUsingFixture");
    }

    @Test
    void thePolicyIsPure() {
        PURE_DOMAIN.check(PRODUCTION);
    }

    private static DescribedPredicate<JavaClass> outsideTheRealBoundary() {
        List<String> allowed = List.of(
                "com.memetrade.riskcontrol.real.",
                "com.memetrade.riskcontrol.api.",
                "com.memetrade.botwallet.api.",
                "com.memetrade.marketdata.api.",
                "com.memetrade.riskassessment.api.",
                "com.memetrade.audit.api.",
                "com.memetrade.shared.",
                // M2: only the REAL reads, never PaperExecution nor the PAPER read model.
                "com.memetrade.ledger.api.Real",
                // 261121-paper-real-unico U2 (BR2.7, logical-components.md "Fronteiras de módulo"): the rounds ask the
                // cost only through the public port of the P&L.
                "com.memetrade.pnl.api.RealWalletCost",
                "com.memetrade.pnl.api.WalletCost",
                "com.memetrade.pnl.api.CostPath",
                // The one shared resource of the module (logical-components.md): the retention pool of the purges.
                "com.memetrade.riskcontrol.infra.RiskRetentionDataSource");
        return new DescribedPredicate<>("a class outside the boundary of the REAL barrier") {
            @Override
            public boolean test(JavaClass type) {
                String name = type.getName();
                return name.startsWith("com.memetrade.") && allowed.stream().noneMatch(name::startsWith);
            }
        };
    }
}
