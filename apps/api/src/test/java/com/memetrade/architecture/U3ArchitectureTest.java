package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.IdentityAdministration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Passo 15 (NFR6.8, NFR6.10, NFR9.3, NFR9.5, infrastructure-specification.md): rules of U3 on the compiled production
 * classes. The key-generation ban of U1 ({@link ArchitectureTest}) keeps covering the U3 code.
 */
@Tag("u3")
class U3ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    /** Only administrative controllers may reach the C17 interface (BR1.5, NFR6.10). */
    static final ArchRule ADMINISTRATION_ONLY_FROM_ADMIN_CONTROLLERS = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .and()
            .haveSimpleNameNotStartingWith("Admin")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(IdentityAdministration.class)
            .because("IdentityAdministration is used only by the administrative routes (NFR6.10)");

    /** Every seeder of test data exists only in the test profile (OQ-8, Q1 of the Infrastructure Design). */
    static final ArchRule SEEDERS_ONLY_IN_THE_TEST_PROFILE = classes()
            .that()
            .haveSimpleNameEndingWith("Seeder")
            .should()
            .beAnnotatedWith(Profile.class)
            .because("seeders exist only with the test profile");

    @Test
    void identityAdministrationIsNeverUsedByAGuestController() {
        ADMINISTRATION_ONLY_FROM_ADMIN_CONTROLLERS.check(PRODUCTION);
    }

    @Test
    void theControllersThatUseIdentityAdministrationMapOnlyAdministrativeRoutes() throws Exception {
        List<String> outside = new ArrayList<>();
        for (JavaClass controller : PRODUCTION) {
            boolean usesAdministration = controller.getDirectDependenciesFromSelf().stream()
                    .anyMatch(d -> d.getTargetClass().isAssignableTo(IdentityAdministration.class));
            if (!controller.isAnnotatedWith(RestController.class) || !usesAdministration) {
                continue;
            }
            for (Method method : Class.forName(controller.getName()).getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) {
                    Arrays.stream(mapping.path())
                            .filter(path -> !path.startsWith("/api/v1/admin/"))
                            .forEach(path -> outside.add(controller.getSimpleName() + " " + path));
                }
            }
        }

        assertThat(outside).isEmpty();
    }

    @Test
    void everySeederExistsOnlyInTheTestProfile() {
        SEEDERS_ONLY_IN_THE_TEST_PROFILE.check(PRODUCTION);
        List<String> seeders = new ArrayList<>();
        for (JavaClass seeder : PRODUCTION) {
            if (seeder.getSimpleName().endsWith("Seeder")) {
                Profile profile = seeder.getAnnotationOfType(Profile.class);
                assertThat(profile.value()).as(seeder.getSimpleName()).containsExactly("test");
                seeders.add(seeder.getSimpleName());
            }
        }
        assertThat(seeders).contains("SmokeAccountSeeder", "SmokeInviteSeeder");
    }

    @Test
    void theRulesDetectAGuestControllerUsingTheAdministrationAndASeederWithoutProfile() {
        JavaClasses violations =
                new ClassFileImporter().importClasses(ForbiddenGuestController.class, UnprofiledSeeder.class);

        assertThatThrownBy(() -> ADMINISTRATION_ONLY_FROM_ADMIN_CONTROLLERS.check(violations))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ForbiddenGuestController");
        assertThatThrownBy(() -> SEEDERS_ONLY_IN_THE_TEST_PROFILE.check(violations))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnprofiledSeeder");
    }

    /**
     * Test-only violation: a guest controller reaching the C17 interface. The profile is never active, so no Spring
     * context of the test suite ever registers it.
     */
    @RestController
    @Profile("archunit-sample-never-active")
    static class ForbiddenGuestController {
        private final IdentityAdministration administration;

        ForbiddenGuestController(IdentityAdministration administration) {
            this.administration = administration;
        }

        @RequestMapping("/api/v1/guest-thing")
        Object counts() {
            return administration.counts(null);
        }
    }

    /** Test-only violation: a seeder outside the test profile. */
    static class UnprofiledSeeder {}
}
