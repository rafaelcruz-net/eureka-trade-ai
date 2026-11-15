package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.memetrade.shared.config.ProcessRole;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 17 of U5 (security-design.md; NFR4.5, NFR6.1, NFR6.4): the boundary of the bots and decision modules. They
 * depend only on the public interfaces of the other modules, never on a network client, a Solana SDK, a signature, a
 * router nor an AI library; no floating point; no {@code BigDecimal.ZERO} standing in for an unknown value; the domain
 * is pure (no Spring, no JDBC, no wall clock); the load beans exist only with the loadtest profile; the worker classes
 * run only in the worker.
 */
@Tag("u5")
class BotsArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final String[] U5 = {"com.memetrade.bots..", "com.memetrade.decision.."};

    private static final Set<Class<?>> FLOATING = Set.of(double.class, float.class, Double.class, Float.class);

    /** security-design.md: outside U5, only the public APIs listed and {@code shared}. */
    @Test
    void botsAndDecisionDependOnlyOnThePublicInterfacesOfOtherModules() {
        noClasses()
                .that()
                .resideInAnyPackage(U5)
                .should()
                .dependOnClassesThat(otherModuleInternals())
                .because("a module talks to another only through its public interface (team.md, security-design.md)")
                .check(PRODUCTION);
    }

    @Test
    void otherModulesReachBotsOnlyThroughItsApi() {
        noClasses()
                .that()
                .resideOutsideOfPackages(U5)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.bots.domain..",
                        "com.memetrade.bots.application..",
                        "com.memetrade.bots.infra..",
                        "com.memetrade.bots.web..",
                        "com.memetrade.bots.worker..",
                        "com.memetrade.decision..")
                .because("U8 and U9 use BotAdministration and BotChangeMarks only (C16, C18)")
                .check(PRODUCTION);
    }

    /** NFR6.1, AC4.3.4: nothing of U5 can sign, send, route nor reach the network. */
    @Test
    void noBotsClassUsesTheNetworkASignatureOrASolanaSdk() {
        noClasses()
                .that()
                .resideInAnyPackage(U5)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.net.http..",
                        "java.net.http",
                        "org.springframework.web.client..",
                        "org.springframework.web.reactive.function.client..",
                        "org.sol4k..",
                        "software.sava..",
                        "org.p2p.solanaj..",
                        "com.memetrade.identity.domain.signature..",
                        "com.memetrade.shared.wallet..")
                .because("the bots never build, sign nor send a transaction (NFR6.1)")
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideInAnyPackage(U5)
                .should()
                .dependOnClassesThat(signingTypes())
                .because("no Signature, Keypair or blockchain Transaction type in U5 (NFR6.1)")
                .check(PRODUCTION);
    }

    /** NFR6.4, AC4.5.5: no AI model library is loaded by the decision. */
    @Test
    void noAiLibraryIsUsed() {
        noClasses()
                .that()
                .resideInAnyPackage(U5)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "ai.djl..",
                        "org.tensorflow..",
                        "ai.onnxruntime..",
                        "dev.langchain4j..",
                        "org.springframework.ai..",
                        "com.openai..",
                        "com.anthropic..",
                        "com.theokanning..")
                .because("the decision is deterministic and uses no AI in the beta (FR8.6)")
                .check(PRODUCTION);
    }

    /** NFR4.5: money, prices, quantities and percentages are BigDecimal. */
    @Test
    void noFieldParameterOrReturnIsFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage(U5)
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
                .areDeclaredInClassesThat()
                .resideInAnyPackage(U5)
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

    /** NFR4.6, C-05: an UNKNOWN market value is never replaced by zero. */
    @Test
    void bigDecimalZeroIsNeverUsed() {
        noClasses()
                .that()
                .resideInAnyPackage(U5)
                .should()
                .accessField(BigDecimal.class, "ZERO")
                .because("a MarketValue.Unknown is never replaced by zero (NFR4.6)")
                .check(PRODUCTION);
    }

    /** team.md: inside the module the domain depends on no infrastructure, web, Spring nor JDBC. */
    @Test
    void theDomainIsPure() {
        noClasses()
                .that()
                .resideInAnyPackage("com.memetrade.bots.domain..", "com.memetrade.decision.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.bots.infra..",
                        "com.memetrade.bots.web..",
                        "com.memetrade.bots.worker..",
                        "com.memetrade.bots.application..",
                        "com.memetrade.decision.infra..",
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..")
                .because("the decision is a pure function and the state machine a pure table (BR4.2, BR2.1)")
                .check(PRODUCTION);
    }

    /** team.md "Determinismo": no wall clock in U5; the instant always comes from the injectable clock. */
    @Test
    void noClassReadsTheWallClock() {
        noClasses()
                .that()
                .resideInAnyPackage(U5)
                .should()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDate.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(Clock.class, "systemUTC")
                .because("every instant comes from the injectable clock (team.md)")
                .check(PRODUCTION);
    }

    /** C20, NFR10.9: the cycle and the jobs exist only in the worker; the routes and the channel only in the api. */
    @Test
    void theWorkerClassesRunOnlyInTheWorkerAndTheWebOnlyInTheApi() {
        classes()
                .that()
                .resideInAPackage("com.memetrade.bots.worker..")
                .and()
                .areTopLevelClasses()
                .and()
                .areAnnotatedWith(org.springframework.stereotype.Component.class)
                .should(role(ProcessRole.Kind.WORKER))
                .check(PRODUCTION);
        classes()
                .that()
                .resideInAPackage("com.memetrade.bots.web..")
                .and()
                .areTopLevelClasses()
                .and()
                .areMetaAnnotatedWith(org.springframework.stereotype.Component.class)
                .should(role(ProcessRole.Kind.API))
                .check(PRODUCTION);
    }

    /** NFR2.1 instrumentation: the load package exists only with the loadtest profile and nothing depends on it. */
    @Test
    void theLoadBeansExistOnlyWithTheLoadtestProfile() {
        classes()
                .that()
                .resideInAPackage("com.memetrade.bots.loadtest..")
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
                .allowEmptyShould(true)
                .check(PRODUCTION);
        noClasses()
                .that()
                .resideOutsideOfPackage("com.memetrade.bots.loadtest..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.memetrade.bots.loadtest..")
                .because("nothing of a normal run depends on the load package")
                .check(PRODUCTION);
    }

    private static ArchCondition<JavaClass> role(ProcessRole.Kind kind) {
        return new ArchCondition<>("carry @ProcessRole(" + kind + ")") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                boolean ok = type.tryGetAnnotationOfType(ProcessRole.class)
                        .map(role -> role.value() == kind)
                        .orElse(false);
                if (!ok) {
                    events.add(
                            SimpleConditionEvent.violated(type, type.getName() + " has no @ProcessRole(" + kind + ")"));
                }
            }
        };
    }

    private static DescribedPredicate<JavaClass> otherModuleInternals() {
        List<String> allowed = List.of(
                "com.memetrade.bots.",
                "com.memetrade.decision.",
                "com.memetrade.riskcontrol.api.",
                "com.memetrade.ledger.api.",
                "com.memetrade.pnl.api.",
                "com.memetrade.marketdata.api.",
                "com.memetrade.riskassessment.api.",
                "com.memetrade.identity.api.",
                "com.memetrade.audit.api.",
                // 261113-trading-real-bot U4: the REAL bot reads the state of the REAL through M1 (botwallet.api) and
                // hands its decisions to the execution through M6 (realexecution.api); never their internals.
                "com.memetrade.botwallet.api.",
                "com.memetrade.realexecution.api.",
                "com.memetrade.shared.");
        return new DescribedPredicate<>("an internal class of another module") {
            @Override
            public boolean test(JavaClass type) {
                String name = type.getName();
                return name.startsWith("com.memetrade.") && allowed.stream().noneMatch(name::startsWith);
            }
        };
    }

    private static DescribedPredicate<JavaClass> signingTypes() {
        List<String> names = List.of("Signature", "Keypair", "KeyPair", "Mnemonic", "SeedPhrase", "Signer");
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
}
