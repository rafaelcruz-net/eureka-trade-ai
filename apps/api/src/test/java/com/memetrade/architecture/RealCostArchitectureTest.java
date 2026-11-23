package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 12 (written before the code; decision F-3, logical-components.md "Fronteiras de
 * módulo"): only the readers that use the cost of a REAL position (the barrier, the REAL cycle and the P&L) depend on
 * {@code RealCost.Known}; the liquidation (realexecution) and the withdrawal (botwallet) never depend on {@code RealCost}
 * at all, so neither can be blocked by the attribution; the buy of the barrier never asks the cost.
 */
@Tag("u2-real-bot-pnl")
class RealCostArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    @Test
    void onlyTheBarrierTheRealCycleAndThePnlDependOnAKnownCost() {
        noClasses()
                .that()
                .resideOutsideOfPackages(
                        "com.memetrade.riskcontrol.real..",
                        "com.memetrade.bots.worker..",
                        "com.memetrade.pnl..",
                        "com.memetrade.ledger.api..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.ledger.api.RealCost$Known")
                .because("only the readers of the cost handle a known cost (F-3)")
                .check(PRODUCTION);
    }

    @Test
    void theLiquidationAndTheWithdrawalNeverDependOnTheCost() {
        noClasses()
                .that()
                .resideInAnyPackage("com.memetrade.realexecution..", "com.memetrade.botwallet..")
                .should()
                .dependOnClassesThat()
                .haveNameMatching("com\\.memetrade\\.ledger\\.api\\.RealCost(\\$.*)?")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.pnl.api.RealWalletCost")
                .because("a sale, a liquidation and a withdrawal never wait for the attribution (BR2.5, BR2.7)")
                .check(PRODUCTION);
    }

    @Test
    void theBuyOfTheBarrierNeverAsksTheCost() {
        methods()
                .that()
                .areDeclaredIn(com.memetrade.riskcontrol.real.infra.RealGateInputsReader.class)
                .and()
                .haveName("buy")
                .should(new ArchCondition<JavaMethod>("never call RealWalletCost") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        method.getMethodCallsFromSelf().stream()
                                .filter(call ->
                                        call.getTargetOwner().getName().equals("com.memetrade.pnl.api.RealWalletCost"))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(
                                        method, method.getFullName() + " calls " + call.getName())));
                    }
                })
                .because("a position without price refuses the buy before the cost is used (R-13, NFR1.26)")
                .check(PRODUCTION);
    }
}
