package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.memetrade.bots.application.RealEligibilityReader;
import com.memetrade.bots.domain.RealCreationEligibility;
import com.memetrade.bots.domain.RealEligibility;
import com.memetrade.pnl.modeview.ModePnlView;
import com.memetrade.shared.config.ProcessRole;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U1, Step 13 (ADR-003, NFR3.4, NFR4.2; security-design.md "A elegibilidade nunca autoriza
 * operar"): the boundaries of the modes.
 *
 * <ul>
 *   <li>the mode view lives in its own package, out of {@code pnl.real}, and {@code pnl.real} stays pure (nothing
 *       PAPER: neither the PAPER ledger nor the PAPER results);
 *   <li>the chain of C4 exists only in the api process;
 *   <li>no path of an order depends on the REAL eligibility of the creation (it only allows creating a bot and never
 *       authorizes trading).
 * </ul>
 */
class ModeBoundaryArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    @Test
    void theModeViewLivesOutOfPnlReal() {
        classes()
                .that()
                .haveFullyQualifiedName(ModePnlView.class.getName())
                .should()
                .resideInAPackage("com.memetrade.pnl.modeview")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.pnl.real..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.memetrade.pnl.modeview..")
                .check(PRODUCTION);
    }

    @Test
    void pnlRealStaysPureWithoutAnythingPaper() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.pnl.real..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.pnl.domain..", "com.memetrade.pnl.application..", "org.springframework..")
                .because("the REAL calculation reads nothing PAPER and stays a pure function (NFR4.2)")
                .check(PRODUCTION);
        // 261121-paper-real-unico U2 (C11): the REAL calculation implements the cost port of the ledger, so it reads
        // the
        // REAL types of ledger.api (the cost path, the network order key, the REAL positions) and never a PAPER one.
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.pnl.real..")
                .should()
                .dependOnClassesThat(
                        new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                                "a PAPER type of ledger.api") {
                            @Override
                            public boolean test(com.tngtech.archunit.core.domain.JavaClass type) {
                                if (!type.getPackageName().equals("com.memetrade.ledger.api")) {
                                    return false;
                                }
                                // The top-level type of a nested one (RealCost$Known is of RealCost).
                                String simple = type.getName()
                                        .substring(type.getPackageName().length() + 1)
                                        .split("\\$")[0];
                                return !simple.startsWith("Real")
                                        && !java.util.List.of(
                                                        "CostFill",
                                                        "CostHolder",
                                                        "NetworkOrderKey",
                                                        "CostPart",
                                                        "Accumulator")
                                                .contains(simple);
                            }
                        })
                .because("the REAL calculation reads nothing PAPER (NFR4.2)")
                .check(PRODUCTION);
    }

    @Test
    void theChainOfTheModeViewExistsOnlyInTheApi() {
        classes()
                .that()
                .resideInAPackage("com.memetrade.pnl.modeview..")
                .and()
                .areTopLevelClasses()
                .and()
                .areAnnotatedWith(org.springframework.stereotype.Component.class)
                .should(new ArchCondition<JavaClass>("be bound to the api process") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        boolean api = type.tryGetAnnotationOfType(ProcessRole.class)
                                .map(role -> role.value() == ProcessRole.Kind.API)
                                .orElse(false);
                        if (!api) {
                            events.add(
                                    SimpleConditionEvent.violated(type, type.getName() + " is not @ProcessRole(API)"));
                        }
                    }
                })
                .check(PRODUCTION);
    }

    @Test
    void noPathOfAnOrderDependsOnTheRealEligibilityOfTheCreation() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "com.memetrade.realexecution..",
                        "com.memetrade.riskcontrol.real..",
                        "com.memetrade.bots.worker..")
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(RealCreationEligibility.class, RealEligibility.class, RealEligibilityReader.class)
                .because("the eligibility only allows creating a REAL bot and never authorizes trading (BR2.6)")
                .check(PRODUCTION);
    }
}
