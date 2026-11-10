package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 8 of u2-retention (logical-components.md, BR2.17, team.md module boundaries): the new Privacy module depends only
 * on the shared package and on the public APIs of other modules, and no module depends on Privacy, so the retention
 * declarations (J7) and the port (J5) never form a cycle.
 */
@Tag("u2-retention")
class PrivacyArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    static final ArchRule PRIVACY_USES_ONLY_SHARED_AND_PUBLIC_APIS = classes()
            .that()
            .resideInAPackage("com.memetrade.privacy..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "com.memetrade.privacy..",
                    "com.memetrade.shared..",
                    "com.memetrade..api..",
                    "java..",
                    "org.springframework..",
                    "org.slf4j..",
                    // u4-data-requests: the infrastructure and web layers of the requests use the same libraries as
                    // every other module (ShedLock for the jobs, Micrometer for the metrics, Jackson for the summary
                    // snapshot, Jakarta Servlet and OpenAPI annotations for the routes). None of them is a module.
                    "net.javacrumbs.shedlock..",
                    "io.micrometer..",
                    "com.fasterxml.jackson..",
                    "jakarta.servlet..",
                    "io.swagger.v3.oas.annotations..")
            .because("Privacy reads no table, repository or internal class of another module (BR2.17, team.md)");

    static final ArchRule NO_MODULE_DEPENDS_ON_PRIVACY = noClasses()
            .that()
            .resideOutsideOfPackage("com.memetrade.privacy..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.memetrade.privacy..")
            .because("the purges declare themselves through shared.retention (J7); nothing depends on Privacy");

    static final ArchRule THE_RETENTION_CONTRACT_HAS_NO_DEPENDENCY_ON_A_MODULE = noClasses()
            .that()
            .resideInAPackage("com.memetrade.shared.retention..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("com.memetrade.shared.retention..", "java..")
            .because("J7 is only the declaration, without logic or dependencies (logical-components.md)");

    @Test
    void privacyDependsOnlyOnSharedAndOnPublicApis() {
        PRIVACY_USES_ONLY_SHARED_AND_PUBLIC_APIS.check(PRODUCTION);
    }

    @Test
    void noModuleDependsOnPrivacy() {
        NO_MODULE_DEPENDS_ON_PRIVACY.check(PRODUCTION);
    }

    @Test
    void theRetentionContractDependsOnNothingButTheJdk() {
        THE_RETENTION_CONTRACT_HAS_NO_DEPENDENCY_ON_A_MODULE.check(PRODUCTION);
    }

    @Test
    void theRuleDetectsAPrivacyClassReachingIntoIdentity() throws ClassNotFoundException {
        JavaClasses probe =
                new ClassFileImporter().importClasses(Class.forName("com.memetrade.privacy.ForbiddenDependencyProbe"));

        assertThatThrownBy(() -> PRIVACY_USES_ONLY_SHARED_AND_PUBLIC_APIS.check(probe))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TermsCatalog");
    }
}
