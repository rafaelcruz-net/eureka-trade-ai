package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.memetrade.riskcontrol.domain.OrderGateService;
import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob;
import com.memetrade.riskcontrol.infra.RiskRetentionDataSource;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 15 of U6 (security-design.md, logical-components.md; NFR4.2, NFR4.5, NFR6.1): the module boundary of
 * {@code riskcontrol}, a domain without infrastructure nor web, no network, signing nor Solana SDK, no floating point,
 * no {@code BigDecimal.ZERO} standing in for an unknown value, the retention pool only in the purge job and the pure
 * gate service reachable only through its transactional bean.
 */
@Tag("u6")
class RiskControlArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final String U6 = "com.memetrade.riskcontrol..";

    /** 261113-trading-real-bot U3: the REAL barrier, a package of its own inside the module. */
    private static final String REAL = "com.memetrade.riskcontrol.real..";

    private static final Set<Class<?>> FLOATING = Set.of(double.class, float.class, Double.class, Float.class);

    /**
     * Outside its own packages, riskcontrol depends only on the public interfaces listed by the plan: C13
     * ({@code pnl.api}), C11 ({@code marketdata.api}), C10 ({@code identity.api}), C9 ({@code audit.api}) and
     * {@code shared}; the web layer also reuses the common {@code MarketValue} response shape of U4 (as U7 does). Since
     * 261113-trading-real-bot U3 also M1 ({@code botwallet.api}), M2 ({@code ledger.api}) and the indicator of the
     * scanner ({@code riskassessment.api}); the narrower rules of the REAL package are in {@code RealRiskArchitectureTest}.
     */
    @Test
    void riskcontrolDependsOnlyOnThePublicInterfacesOfOtherModules() {
        noClasses()
                .that()
                .resideInAPackage(U6)
                .and()
                .resideOutsideOfPackage("com.memetrade.riskcontrol.loadtest..")
                .should()
                .dependOnClassesThat(otherModuleInternals())
                .because("a module talks to another only through its public interface (team.md, plan)")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage(U6)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.ledger.domain..",
                        "com.memetrade.ledger.application..",
                        "com.memetrade.ledger.infra..",
                        "com.memetrade.ledger.web..",
                        "com.memetrade.pnl.domain..",
                        "com.memetrade.pnl.application..",
                        "com.memetrade.pnl.infra..")
                .because("U6 reads U7 only through PortfolioRiskView (NFR6.1)")
                .check(PRODUCTION);
    }

    @Test
    void otherModulesDependOnlyOnTheApiOfRiskcontrol() {
        noClasses()
                .that()
                .resideOutsideOfPackage(U6)
                .and()
                // U4 of 261113-trading-real-bot (U4-NFR3.9): the purge of the REAL execution receives the retention
                // pool of U6 and nothing else of the module (RealExecutionArchitectureTest checks the rest).
                .doNotHaveFullyQualifiedName(REAL_EXECUTION_PURGE_JOB)
                .and()
                // 261113-trading-real-bot U5 (U5-NFR3.9): the purge of the operations of the REAL, likewise.
                .doNotHaveFullyQualifiedName(REAL_OPS_PURGE_JOB)
                .and()
                // 261113-trading-real-bot U7 (U7-NFR3.9): the purge of the conditional exits, likewise.
                .doNotHaveFullyQualifiedName(REAL_EXITS_PURGE_JOB)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.riskcontrol.domain..",
                        "com.memetrade.riskcontrol.infra..",
                        "com.memetrade.riskcontrol.web..",
                        "com.memetrade.riskcontrol.real..")
                .because("U5 reaches the barrier only through OrderGate (C14); U4 and U5 of the REAL through M5")
                .check(PRODUCTION);
    }

    @Test
    void theDomainDependsOnNeitherInfrastructureNorWebNorSpringNorJdbc() {
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.riskcontrol.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.riskcontrol.infra..",
                        "com.memetrade.riskcontrol.web..",
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..")
                .because("team.md: inside a module the domain depends on no infrastructure nor web class")
                .check(PRODUCTION);
    }

    @Test
    void noRiskcontrolClassUsesTheNetworkASignatureOrASolanaSdk() {
        noClasses()
                .that()
                .resideInAPackage(U6)
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
                .because("the barrier never builds, signs nor sends a transaction (NFR6.1)")
                .check(PRODUCTION);
        // The PAPER barrier never executes nor reads the ledger; the REAL barrier (261113-trading-real-bot U3) reads
        // only the REAL reads of M2 (balance, positions, flows), never PaperExecution (RealRiskArchitectureTest).
        noClasses()
                .that()
                .resideInAPackage(U6)
                .and()
                .resideOutsideOfPackage(REAL)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.memetrade.ledger.api..")
                .because("the PAPER barrier never executes (NFR6.1)")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage(U6)
                .and()
                .resideOutsideOfPackage("com.memetrade.riskcontrol.loadtest..")
                .should()
                .dependOnClassesThat(signingTypes())
                .because("no Signature, Keypair or blockchain Transaction type in RiskControl (NFR6.1)")
                .check(PRODUCTION);
    }

    @Test
    void noRiskcontrolFieldParameterOrReturnIsFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAPackage(U6)
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .because("money, prices, quantities and percentages are BigDecimal (project.md, NFR4.2)")
                .check(PRODUCTION);
        codeUnits()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage(U6)
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

    /**
     * NFR4.5: no {@code BigDecimal.ZERO} anywhere in riskcontrol, so a missing value can never be replaced by zero; a
     * real zero (the equity drop of a sell) is a named constant of the domain, never derived from an unknown value.
     */
    @Test
    void bigDecimalZeroIsNeverUsedInRiskcontrol() {
        noClasses()
                .that()
                .resideInAPackage(U6)
                .should()
                .accessField(BigDecimal.class, "ZERO")
                .because("a MarketValue.Unknown is never replaced by zero (C-05, NFR4.5)")
                .check(PRODUCTION);
    }

    /**
     * 261113-trading-real-bot U3 (scalability-design.md, logical-components.md "Desvio de Q1 e Q6"): the REAL purge of
     * 04:00 UTC uses the same retention pool, one round after the PAPER one.
     */
    private static final String REAL_PURGE_JOB = "com.memetrade.riskcontrol.real.infra.RealRiskPurgeJob";

    /** 261113-trading-real-bot U4 (U4-NFR3.9, scalability-design.md): the purge of the REAL execution at 04:30 UTC. */
    private static final String REAL_EXECUTION_PURGE_JOB = "com.memetrade.realexecution.infra.RealExecutionPurgeJob";

    /** 261113-trading-real-bot U5 (U5-NFR3.9): the purge of the operations of the REAL at 05:00 UTC. */
    private static final String REAL_OPS_PURGE_JOB = "com.memetrade.botwallet.infra.RealOpsPurgeJob";

    /** 261113-trading-real-bot U7 (U7-NFR3.9): the purge of the final conditional exits at 05:30 UTC. */
    private static final String REAL_EXITS_PURGE_JOB = "com.memetrade.realexits.infra.RealExitsPurgeJob";

    @Test
    void onlyThePurgeJobReceivesTheRetentionPoolOfU6() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(RiskEvaluationPurgeJob.class.getName())
                .and()
                .doNotHaveFullyQualifiedName(RiskRetentionDataSource.class.getName())
                .and()
                .doNotHaveFullyQualifiedName("com.memetrade.riskcontrol.infra.RiskRetentionDataSourceConfig")
                .and()
                .doNotHaveFullyQualifiedName(REAL_PURGE_JOB)
                .and()
                .doNotHaveFullyQualifiedName(REAL_EXECUTION_PURGE_JOB)
                .and()
                .doNotHaveFullyQualifiedName(REAL_OPS_PURGE_JOB)
                .and()
                .doNotHaveFullyQualifiedName(REAL_EXITS_PURGE_JOB)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(RiskRetentionDataSource.class)
                .because("the retention login may delete evaluations; only the purge receives the pool")
                .check(PRODUCTION);
        noClasses()
                .that()
                .haveFullyQualifiedName(RiskEvaluationPurgeJob.class.getName())
                .or()
                .haveFullyQualifiedName(REAL_PURGE_JOB)
                .or()
                .haveFullyQualifiedName(REAL_EXECUTION_PURGE_JOB)
                .or()
                .haveFullyQualifiedName(REAL_OPS_PURGE_JOB)
                .or()
                .haveFullyQualifiedName(REAL_EXITS_PURGE_JOB)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.memetrade.audit.infra.AuditRetentionDataSource")
                .because("each Unit uses its own retention pool (infrastructure-specification.md)")
                .check(PRODUCTION);
    }

    /** The pure gate never runs outside the cycle transaction: only its transactional bean and the wiring use it. */
    @Test
    void thePureGateServiceIsReachableOnlyThroughTheTransactionalBean() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("com.memetrade.riskcontrol.infra.TransactionalOrderGate")
                .and()
                .doNotHaveFullyQualifiedName("com.memetrade.riskcontrol.infra.RiskControlConfig")
                .and()
                .doNotHaveFullyQualifiedName(OrderGateService.class.getName())
                .should()
                .dependOnClassesThat()
                .areAssignableTo(OrderGateService.class)
                .because("OrderGate requires the cycle transaction (BR1.1)")
                .check(PRODUCTION);
        classes()
                .that()
                .haveFullyQualifiedName("com.memetrade.riskcontrol.infra.TransactionalOrderGate")
                .should()
                .beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                .check(PRODUCTION);
    }

    /**
     * Passo 16: EVERY top-level class of {@code riskcontrol.loadtest} carries {@code @Profile("loadtest")} and the
     * package is not empty; nothing of a normal run depends on it; it reaches the barrier through OrderGate, the purge
     * through its job and the people and bots through the load package of U7, never tables nor JDBC.
     * {@code LoadtestRiskBeansProfileTest} confirms that none of them is a bean of the default or the test profile.
     */
    @Test
    void theLoadTestBeansExistOnlyWithTheLoadtestProfile() {
        classes()
                .that()
                .resideInAPackage("com.memetrade.riskcontrol.loadtest..")
                .and()
                .areTopLevelClasses()
                .should(new ArchCondition<JavaClass>("be annotated with @Profile(\"loadtest\")") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        boolean ok = type.tryGetAnnotationOfType(org.springframework.context.annotation.Profile.class)
                                .map(profile -> List.of(profile.value()).contains("loadtest"))
                                .orElse(false);
                        if (!ok) {
                            events.add(SimpleConditionEvent.violated(type, type.getName() + " has no @Profile"));
                        }
                    }
                })
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideOutsideOfPackage("com.memetrade.riskcontrol.loadtest..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.memetrade.riskcontrol.loadtest..")
                .because("nothing of a normal run depends on the load package")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAPackage("com.memetrade.riskcontrol.loadtest..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.jdbc..",
                        "com.memetrade.riskcontrol.web..",
                        "com.memetrade.ledger.domain..",
                        "com.memetrade.ledger.application..",
                        "com.memetrade.ledger.infra..",
                        "com.memetrade.identity.domain..",
                        "com.memetrade.identity.infra..")
                .because("the load bots use the public interfaces and the repositories of U6, never tables")
                .check(PRODUCTION);
    }

    private static DescribedPredicate<JavaClass> otherModuleInternals() {
        List<String> allowed = List.of(
                "com.memetrade.riskcontrol.",
                "com.memetrade.pnl.api.",
                "com.memetrade.marketdata.api.",
                "com.memetrade.identity.api.",
                "com.memetrade.audit.api.",
                "com.memetrade.shared.",
                "com.memetrade.scanner.web.ScannerDtos",
                // 261113-trading-real-bot U3 (U3-NFR8.1): M1, M2 and the indicator of the scanner, for the REAL
                // barrier.
                "com.memetrade.botwallet.api.",
                "com.memetrade.ledger.api.",
                "com.memetrade.riskassessment.api.");
        return new DescribedPredicate<>("an internal class of another module") {
            @Override
            public boolean test(JavaClass type) {
                String name = type.getName();
                return name.startsWith("com.memetrade.") && allowed.stream().noneMatch(name::startsWith);
            }
        };
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
                        && !type.getPackageName().startsWith("java")
                        && !type.getName().equals("com.memetrade.riskcontrol.infra.TransactionalOrderGate")
                        && !type.getName().equals("com.memetrade.riskcontrol.real.infra.TransactionalRealRiskGate");
                return signing || chainTransaction;
            }
        };
    }
}
