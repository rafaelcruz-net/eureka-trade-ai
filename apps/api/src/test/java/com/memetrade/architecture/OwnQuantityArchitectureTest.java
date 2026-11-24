package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.architecture.ownquantity.HoldingsOutsideTheCycleSample;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5, T13 (written before the code; security-design.md R1 to R6; BR2.1, BR4.1, BR4.3,
 * BR4.4, NFR1.4, NFR2.6, NFR3.3, NFR3.5, NFR4.2): the boundaries of the read of the own holdings.
 *
 * <ol>
 *   <li>R1: outside the {@code pnl} that owns it, only the cycle of the worker ({@code bots.worker}) reads the own
 *       holdings ({@code RealBotHoldings}, {@code HoldingsResult});
 *   <li>R2: the paths of the money (the execution, the exits, the bot wallet with the withdrawal and "vender tudo e
 *       sacar") never depend on the P&L; the barrier ({@code riskcontrol}) never depends on the own holdings (it
 *       already reads the cost of the wallet of the U2 through {@code pnl.api}, which the U4 does not change);
 *   <li>R3: the reader takes no lock: no {@code LockOrder}, {@code WalletLock}, {@code RealRiskGate} nor
 *       {@code JdbcCycleRepository};
 *   <li>R4: the new classes use no floating point;
 *   <li>R5: the new classes never touch the PAPER (its execution, its ledger read model, its P&L types);
 *   <li>R6: the cut {@code OwnSellCap} is pure: no infrastructure, no Spring, no JDBC.
 * </ol>
 *
 * R1 is proven on a sample made to break it.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    /** The classes the U4 adds (production). */
    static final Set<String> NEW_CLASSES = Set.of(
            "OwnSellCap",
            "SellCapOutcome",
            "OwnQuantityNote",
            "OwnQuantityReason",
            "OwnDecisionInputs",
            "OwnHoldings",
            "OwnPosition",
            "OwnHoldingsStep",
            "OwnHoldingsStreaks",
            "HoldingsSnapshot",
            "DispatchedBot",
            "OwnQuantityMetrics",
            "OwnQuantitySellProperties",
            "OwnQuantitySell",
            "RealBotHoldings",
            "HoldingsResult",
            "BotHolding",
            "UnavailableReason",
            "RealBotHoldingsReader",
            "EpochAttribution",
            "OpenSell",
            "OpenSellSource",
            "OwnQuantityContext");

    private static final DescribedPredicate<JavaClass> NEW = new DescribedPredicate<>("a class added by the U4") {
        @Override
        public boolean test(JavaClass type) {
            String top = type.getName().substring(type.getPackageName().length() + 1);
            String outer = top.contains("$") ? top.substring(0, top.indexOf('$')) : top;
            boolean own = type.getPackageName().startsWith("com.memetrade.bots")
                    || type.getPackageName().startsWith("com.memetrade.pnl")
                    || type.getPackageName().startsWith("com.memetrade.realexecution.api")
                    || type.getPackageName().startsWith("com.memetrade.decision.infra");
            // pnl.api.UnavailableReason only (riskassessment.api has another enum of that name).
            return own && NEW_CLASSES.contains(outer);
        }
    };

    private static final DescribedPredicate<JavaClass> OWN_HOLDINGS_READ =
            new DescribedPredicate<>("the read of the own holdings") {
                @Override
                public boolean test(JavaClass type) {
                    String name = type.getName();
                    return name.equals("com.memetrade.pnl.api.RealBotHoldings")
                            || name.startsWith("com.memetrade.pnl.api.HoldingsResult");
                }
            };

    static ArchRule r1() {
        return noClasses()
                .that()
                .resideOutsideOfPackages("com.memetrade.bots.worker..", "com.memetrade.pnl..")
                .should()
                .dependOnClassesThat(OWN_HOLDINGS_READ)
                .because("only the cycle of the worker reads the own holdings (security-design.md R1)");
    }

    @Test
    void r1OnlyTheCycleOfTheWorkerReadsTheOwnHoldings() {
        r1().check(PRODUCTION);
    }

    @Test
    void r1FailsOnASampleMadeToBreakIt() {
        JavaClasses sample = new ClassFileImporter().importClasses(HoldingsOutsideTheCycleSample.class);
        assertThatThrownBy(() -> r1().check(sample)).isInstanceOf(AssertionError.class);
    }

    @Test
    void r2ThePathsOfTheMoneyNeverDependOnThePnlNorTheBarrierOnTheOwnHoldings() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "com.memetrade.realexecution..", "com.memetrade.realexits..", "com.memetrade.botwallet..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.memetrade.pnl..")
                .because("a withdrawal, a liquidation, a conditional and a sale never read the attribution (BR4.1)")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAnyPackage("com.memetrade.riskcontrol..")
                .should()
                .dependOnClassesThat(OWN_HOLDINGS_READ)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.pnl.api.BotHolding")
                .check(PRODUCTION);
    }

    @Test
    void r3TheReaderTakesNoLock() {
        noClasses()
                .that()
                .haveNameMatching("com\\.memetrade\\.pnl\\.application\\.(RealBotHoldings.*|EpochAttribution.*)")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.bots.infra.LockOrder")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.riskcontrol.real.infra.WalletLock")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.riskcontrol.api.RealRiskGate")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.bots.infra.JdbcCycleRepository")
                .because("the read is a snapshot without any lock (NFR1.4)")
                .check(PRODUCTION);
    }

    @Test
    void r4TheNewClassesUseNoFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat(NEW)
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .check(PRODUCTION);
        codeUnits()
                .that()
                .areDeclaredInClassesThat(NEW)
                .should(new ArchCondition<JavaCodeUnit>("use no floating point parameter or return") {
                    @Override
                    public void check(JavaCodeUnit unit, ConditionEvents events) {
                        boolean floating =
                                Set.<Class<?>>of(double.class, float.class, Double.class, Float.class).stream()
                                        .anyMatch(
                                                type -> unit.getRawReturnType().isEquivalentTo(type)
                                                        || unit.getRawParameterTypes().stream()
                                                                .anyMatch(p -> p.isEquivalentTo(type)));
                        if (floating) {
                            events.add(
                                    SimpleConditionEvent.violated(unit, unit.getFullName() + " uses floating point"));
                        }
                    }
                })
                .check(PRODUCTION);
    }

    @Test
    void r5TheNewClassesNeverTouchThePaper() {
        noClasses()
                .that(NEW)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.ledger.api.PaperExecution")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.ledger.api.LedgerReadModel")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleNameStartingWith("Paper")
                .check(PRODUCTION);
    }

    @Test
    void r6TheCutIsPure() {
        noClasses()
                .that()
                .haveFullyQualifiedName("com.memetrade.bots.domain.OwnSellCap")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.bots.infra..",
                        "com.memetrade.bots.worker..",
                        "com.memetrade.pnl..",
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..")
                .check(PRODUCTION);
    }
}
