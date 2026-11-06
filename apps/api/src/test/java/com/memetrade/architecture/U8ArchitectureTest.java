package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.administration.infra.KillSwitchApplier;
import com.memetrade.bots.api.BotAdministration;
import com.memetrade.feedback.infra.FeedbackPurgeJob;
import com.memetrade.feedback.infra.FeedbackRetentionDataSource;
import com.memetrade.feedback.infra.FeedbackRetentionDataSourceConfig;
import com.memetrade.shared.config.ProcessRole;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 15 of U8 (security-design.md "Separação de papéis", logical-components.md "Isolamento", team.md module
 * boundaries, project.md): rules of the Administration and Feedback modules on the compiled production classes.
 */
@Tag("u8")
class U8ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final String[] U8 = {"com.memetrade.administration..", "com.memetrade.feedback.."};

    /** AC7.4.2, BR4.1: the administration never reaches the ledger, the P&L or the PAPER execution. */
    static final ArchRule NO_LEDGER_PNL_OR_PAPER = noClasses()
            .that()
            .resideInAnyPackage(U8)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.memetrade.ledger..", "com.memetrade.pnl..", "com.memetrade.decision..")
            .because("the administrator never reads the ledger, the P&L or a trade of a person (AC7.4.2)");

    /** team.md: a module talks to another one only through its public interface, never its internals. */
    static final ArchRule ONLY_PUBLIC_INTERFACES_OF_OTHER_MODULES = noClasses()
            .that()
            .resideInAnyPackage(U8)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.memetrade.bots.application..",
                    "com.memetrade.bots.domain..",
                    "com.memetrade.bots.infra..",
                    "com.memetrade.riskcontrol.domain..",
                    "com.memetrade.riskcontrol.infra..",
                    "com.memetrade.audit.domain..",
                    "com.memetrade.audit.infra..",
                    "com.memetrade.marketdata.domain..",
                    "com.memetrade.marketdata.infra..",
                    "com.memetrade.identity.domain..",
                    "com.memetrade.identity.infra..",
                    "com.memetrade.identity.web..")
            .because("U8 uses only bots.api, riskcontrol.api, audit.api, marketdata.api and identity.api (C16, C17)");

    /** BR2.8: only the applier calls the system pause of U5, always under the apply lock. */
    static final ArchRule ONLY_THE_APPLIER_PAUSES_THE_SYSTEM = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(KillSwitchApplier.class.getName())
            .and()
            .resideOutsideOfPackage("com.memetrade.bots..")
            .should()
            .accessTargetWhere(new DescribedPredicate<JavaAccess<?>>("BotAdministration.apply/liftSystemPause") {
                @Override
                public boolean test(JavaAccess<?> access) {
                    return access.getTargetOwner().isAssignableTo(BotAdministration.class)
                            && (access.getName().equals("applySystemPause")
                                    || access.getName().equals("liftSystemPause"));
                }
            })
            .because("the order of BR2.8 holds only if every call goes through the apply lock");

    /** project.md: no floating point anywhere in U8 (counts, versions and limits are integers). */
    static final ArchRule NO_FLOATING_POINT = fields().that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(U8)
            .should()
            .notHaveRawType(double.class)
            .andShould()
            .notHaveRawType(float.class)
            .andShould()
            .notHaveRawType(Double.class)
            .andShould()
            .notHaveRawType(Float.class);

    /** project.md: the Solana SDK lives only in test code; production verifies Ed25519 natively. */
    static final ArchRule NO_SOLANA_SDK = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.p2p.solanaj..", "org.bitcoinj..", "org.sol4k..", "software.sava..")
            .because("the Solana SDK and key libraries are test-only (project.md)");

    /** NFR6.2: the retention pool reaches only the purge. */
    static final ArchRule RETENTION_POOL_ONLY_IN_THE_PURGE = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(FeedbackPurgeJob.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(FeedbackRetentionDataSourceConfig.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(FeedbackRetentionDataSource.class.getName())
            .should()
            .dependOnClassesThat()
            .areAssignableTo(FeedbackRetentionDataSource.class)
            .because("the retention login deletes comments; only the purge receives it");

    /** logical-components.md: the worker runs nothing of U8; every job of U8 carries @ProcessRole(API). */
    static final ArchRule JOBS_ONLY_IN_THE_API = classes()
            .that()
            .resideInAnyPackage(U8)
            .and(new DescribedPredicate<JavaClass>("run on their own (@Scheduled or ApplicationReadyEvent)") {
                @Override
                public boolean test(JavaClass type) {
                    return type.getMethods().stream()
                            .anyMatch(method -> method.isAnnotatedWith(
                                            org.springframework.scheduling.annotation.Scheduled.class)
                                    || method.isAnnotatedWith(org.springframework.context.event.EventListener.class));
                }
            })
            .should(apiRole());

    @Test
    void theAdministrationAndTheFeedbackNeverReachTheLedgerThePnlOrThePaperExecution() {
        NO_LEDGER_PNL_OR_PAPER.check(PRODUCTION);
    }

    @Test
    void theyUseOnlyThePublicInterfacesOfTheOtherModules() {
        ONLY_PUBLIC_INTERFACES_OF_OTHER_MODULES.check(PRODUCTION);
    }

    @Test
    void onlyTheApplierCallsTheSystemPauseOfU5() {
        ONLY_THE_APPLIER_PAUSES_THE_SYSTEM.check(PRODUCTION);
    }

    @Test
    void noFloatingPointInU8() {
        NO_FLOATING_POINT.check(PRODUCTION);
    }

    @Test
    void noSolanaSdkInProduction() {
        NO_SOLANA_SDK.check(PRODUCTION);
    }

    @Test
    void theRetentionPoolReachesOnlyThePurge() {
        RETENTION_POOL_ONLY_IN_THE_PURGE.check(PRODUCTION);
    }

    @Test
    void everyJobOfU8RunsOnlyInTheApi() {
        JOBS_ONLY_IN_THE_API.check(PRODUCTION);
    }

    /** team.md: no module reads the tables of another; U8 names only its own tables (and none of identity). */
    @Test
    void noU8SourceNamesATableOfAnotherModule() throws IOException {
        List<String> foreign = List.of(
                "app_user", "wallet", "tenant ", "user_session", "bot ", "order_rejection", "audit_outbox", "ledger");
        List<String> offenders;
        try (Stream<Path> files = Stream.concat(
                Files.walk(Path.of("src", "main", "java", "com", "memetrade", "administration")),
                Files.walk(Path.of("src", "main", "java", "com", "memetrade", "feedback")))) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            return foreign.stream()
                                    .anyMatch(table -> source.contains("from " + table)
                                            || source.contains("into " + table)
                                            || source.contains("update " + table)
                                            || source.contains("join " + table));
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }

    /** The rules are not vacuous: the controllable double of the tests calls the pause outside the applier. */
    @Test
    void theRulesDetectAViolation() throws ClassNotFoundException {
        JavaClasses testOnly =
                new ClassFileImporter().importClasses(Class.forName("com.memetrade.administration.KillSwitchCrashIT"));

        assertThatThrownBy(() -> ONLY_THE_APPLIER_PAUSES_THE_SYSTEM.check(testOnly))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SystemPause");
    }

    private static ArchCondition<JavaClass> apiRole() {
        return new ArchCondition<>("carry @ProcessRole(API)") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                boolean api = type.tryGetAnnotationOfType(ProcessRole.class)
                        .map(role -> role.value() == ProcessRole.Kind.API)
                        .orElse(false);
                if (!api) {
                    events.add(SimpleConditionEvent.violated(type, type.getName() + " has no @ProcessRole(API)"));
                }
            }
        };
    }
}
