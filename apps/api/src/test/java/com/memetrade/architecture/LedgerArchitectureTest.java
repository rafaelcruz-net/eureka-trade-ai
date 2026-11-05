package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 20 (logical-components.md, security-design.md; NFR6.1, NFR6.4, NFR6.7, BR1.9): the module boundaries of
 * {@code ledger} and {@code pnl}, no network, signing nor Solana SDK, no floating point, the compensation reachable
 * only from the domain, and the load test beans only with the {@code loadtest} profile.
 */
@Tag("u7")
class LedgerArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final String[] U7 = {"com.memetrade.ledger..", "com.memetrade.pnl.."};

    private static final Set<Class<?>> FLOATING = Set.of(double.class, float.class, Double.class, Float.class);

    @Test
    void thePnlModuleUsesOnlyTheApiOfTheLedger() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.pnl..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.ledger.domain..",
                        "com.memetrade.ledger.application..",
                        "com.memetrade.ledger.infra..",
                        "com.memetrade.ledger.web..",
                        "com.memetrade.ledger.loadtest..")
                .because("pnl reads the ledger only through LedgerReadModel and FillRecorded (logical-components.md)")
                .check(PRODUCTION);
    }

    @Test
    void theLedgerNeverDependsOnThePnl() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.ledger..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.memetrade.pnl..")
                .because("the ledger publishes FillRecorded and never calls the P&L")
                .check(PRODUCTION);
    }

    @Test
    void theDomainsDependOnNeitherInfrastructureNorWebNorSpring() {
        noClasses()
                .that()
                .resideInAnyPackage("com.memetrade.ledger.domain..", "com.memetrade.pnl.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..infra..", "..web..", "..application..", "org.springframework..", "java.sql..")
                .because("team.md: inside a module the domain depends on no infrastructure nor web class")
                .check(PRODUCTION);
    }

    @Test
    void noU7ClassUsesTheNetworkASignatureOrASolanaSdk() {
        noClasses()
                .that()
                .resideInAnyPackage(U7)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.net.http..",
                        "org.springframework.web.client..",
                        "org.springframework.web.reactive.function.client..",
                        "org.sol4k..",
                        "software.sava..",
                        "org.p2p.solanaj..",
                        "com.memetrade.identity.domain.signature..",
                        "com.memetrade.shared.wallet..")
                .because("PAPER never builds, signs nor sends a transaction (BR1.9, NFR6.1)")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAnyPackage(U7)
                .should()
                .dependOnClassesThat(signingTypes())
                .because("no Signature, Keypair or blockchain Transaction type in the ledger (NFR6.1)")
                .check(PRODUCTION);
    }

    @Test
    void noU7FieldParameterOrReturnIsFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage(U7)
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .because("money, quantities and P&L are BigDecimal (project.md, NFR6.7)")
                .check(PRODUCTION);
        codeUnits()
                .that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage(U7)
                .should(new ArchCondition<JavaCodeUnit>("use no floating point parameter or return") {
                    @Override
                    public void check(JavaCodeUnit unit, ConditionEvents events) {
                        boolean floating = FLOATING.stream()
                                .anyMatch(type -> unit.getRawReturnType().isEquivalentTo(type)
                                        || unit.getRawParameterTypes().stream().anyMatch(p -> p.isEquivalentTo(type)));
                        if (floating) {
                            events.add(
                                    SimpleConditionEvent.violated(unit, unit.getFullName() + " uses floating point"));
                        }
                    }
                })
                .check(PRODUCTION);
    }

    @Test
    void theCompensationIsReachableOnlyFromTheLedgerDomain() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.memetrade.ledger.domain")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.ledger.domain.LedgerCompensation")
                .because("no route nor screen corrects the ledger in the beta (NFR6.4)")
                .check(PRODUCTION);
        classes()
                .that()
                .haveFullyQualifiedName("com.memetrade.ledger.domain.LedgerCompensation")
                .should()
                .notBePublic()
                .check(PRODUCTION);
    }

    /**
     * Passo 23: EVERY top-level class of {@code ledger.loadtest} (the load cycle driver, its seed, its tokens, its
     * authentication context and its health component) carries {@code @Profile("loadtest")}, and the package is not
     * empty. {@code LoadtestBeansProfileTest} and {@code U7ContextIT} confirm that none of them is a bean of the default
     * or the test profile.
     */
    @Test
    void theLoadTestBeansExistOnlyWithTheLoadtestProfile() {
        classes()
                .that()
                .resideInAPackage("com.memetrade.ledger.loadtest..")
                .and()
                .areTopLevelClasses()
                .should(loadtestProfile())
                .because("the cycle driver and the seed never reach a normal run (Passo 23)")
                .check(PRODUCTION);
    }

    /** The load package reaches the ledger only through its public interface, like U5 will. */
    @Test
    void theLoadTestPackageUsesOnlyTheApiOfTheLedger() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.ledger.loadtest..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.ledger.domain..",
                        "com.memetrade.ledger.application..",
                        "com.memetrade.ledger.infra..",
                        "com.memetrade.ledger.web..",
                        "com.memetrade.identity.domain..",
                        "com.memetrade.identity.infra..",
                        "com.memetrade.identity.web..",
                        "com.memetrade.marketdata.domain..",
                        "com.memetrade.marketdata.infra..",
                        "org.springframework.jdbc..")
                .because("the load bots call PaperExecution, C10 and C11 only, never tables nor internals")
                .check(PRODUCTION);
        // U6 (Passo 16): the load package of RiskControl, itself only in the loadtest profile, reuses the prepared
        // bots.
        noClasses()
                .that()
                .resideOutsideOfPackages("com.memetrade.ledger.loadtest..", "com.memetrade.riskcontrol.loadtest..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.memetrade.ledger.loadtest..")
                .because("nothing of a normal run depends on the load package")
                .check(PRODUCTION);
    }

    private static DescribedPredicate<JavaClass> signingTypes() {
        List<String> names = List.of("Signature", "Keypair", "KeyPair", "Mnemonic", "SeedPhrase");
        return new DescribedPredicate<>("a signing, key or blockchain transaction type") {
            @Override
            public boolean test(JavaClass type) {
                String simple = type.getSimpleName();
                boolean signing = names.stream().anyMatch(simple::contains);
                boolean chainTransaction = simple.contains("Transaction")
                        && !type.getPackageName().startsWith("org.springframework")
                        && !type.getPackageName().startsWith("java");
                return signing || chainTransaction;
            }
        };
    }

    private static ArchCondition<JavaClass> loadtestProfile() {
        return new ArchCondition<>("be annotated with @Profile(\"loadtest\")") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                boolean ok = type.tryGetAnnotationOfType(org.springframework.context.annotation.Profile.class)
                        .map(profile -> List.of(profile.value()).contains("loadtest"))
                        .orElse(false);
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(type, type.getName() + " has no @Profile(\"loadtest\")"));
                }
            }
        };
    }
}
