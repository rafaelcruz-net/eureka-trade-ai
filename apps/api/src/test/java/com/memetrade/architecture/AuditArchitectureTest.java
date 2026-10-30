package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.infra.AuditRetentionDataSource;
import com.memetrade.audit.infra.AuditRetentionDataSourceConfig;
import com.memetrade.audit.infra.AuditRetentionPurge;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.web.AdminAuthorization;
import com.memetrade.identity.web.AuthenticationFilter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U2 rules (security-design.md, NFR6.1 to NFR6.3, NFR5.1) on the compiled production classes:
 *
 * <ul>
 *   <li>a {@link Principal} is created only on the session authentication path ({@link SessionService}, called only
 *       by the session filter {@link AuthenticationFilter}), so no production code can make an administrator
 *       context before U8;
 *   <li>{@link Role#ADMIN} is referenced only by the administrative authorisation component
 *       {@link AdminAuthorization}, which the route rule {@code AdminRouteFilter} and every administrative operation
 *       (through {@code AdminAccess}) use; every {@code Admin*Controller} depends on {@code AdminAccess};
 *   <li>no class outside the audit module depends on its internals ({@code audit.domain}, {@code audit.infra},
 *       {@code audit.web});
 *   <li>the retention pool reaches only the retention purge.
 * </ul>
 */
@Tag("u2")
class AuditArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    static final ArchRule PRINCIPAL_ONLY_FROM_THE_SESSION_PATH = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(SessionService.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(Principal.class.getName())
            .should()
            .callConstructorWhere(new DescribedPredicate<JavaConstructorCall>("a constructor of Principal") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    return call.getTargetOwner().isEquivalentTo(Principal.class);
                }
            })
            .because("the principal comes only from a validated session (NFR6.1); the admin entry arrives in U8");

    static final ArchRule AUTHENTICATE_ONLY_FROM_THE_SESSION_FILTER = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(AuthenticationFilter.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(SessionService.class.getName())
            .should()
            .accessTargetWhere(new DescribedPredicate<JavaAccess<?>>("SessionService.authenticate") {
                @Override
                public boolean test(JavaAccess<?> access) {
                    return access.getTargetOwner().isAssignableTo(SessionService.class)
                            && "authenticate".equals(access.getName());
                }
            })
            .because("only the session filter turns a bearer token into a principal");

    static final ArchRule ADMIN_ONLY_IN_THE_ADMIN_ROUTE_RULE = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(AdminAuthorization.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(Role.class.getName())
            .should()
            .accessField(Role.class, "ADMIN")
            .because("only the administrative authorisation component, used by the route rule and by each "
                    + "administrative operation, decides on the ADMIN role (BR2.1, NFR6.1)");

    /** The route rule and the administrative operations decide through that single component. */
    static final ArchRule ADMIN_OPERATIONS_CHECK_THE_ROLE =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                    .that()
                    .haveSimpleNameStartingWith("Admin")
                    .and()
                    .haveSimpleNameEndingWith("Controller")
                    .should()
                    .dependOnClassesThat()
                    .areAssignableTo(com.memetrade.identity.api.AdminAccess.class)
                    .because("each administrative operation refuses non-administrators itself (defence in depth)");

    static final ArchRule AUDIT_INTERNALS_STAY_INSIDE_THE_MODULE = noClasses()
            .that()
            .resideOutsideOfPackage("com.memetrade.audit..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.memetrade.audit.domain..", "com.memetrade.audit.infra..", "com.memetrade.audit.web..")
            .because("other modules use only audit.api; no module reads the trail tables (NFR5.1)");

    static final ArchRule RETENTION_POOL_ONLY_IN_THE_PURGE = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(AuditRetentionPurge.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(AuditRetentionDataSourceConfig.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(AuditRetentionDataSource.class.getName())
            .should()
            .dependOnClassesThat()
            .areAssignableTo(AuditRetentionDataSource.class)
            .because("the retention login may delete trail events; only the purge receives it (NFR6.2)");

    @Test
    void aPrincipalIsCreatedOnlyOnTheSessionAuthenticationPath() {
        PRINCIPAL_ONLY_FROM_THE_SESSION_PATH.check(PRODUCTION);
        AUTHENTICATE_ONLY_FROM_THE_SESSION_FILTER.check(PRODUCTION);
    }

    @Test
    void theAdminRoleIsReferencedOnlyByTheAdministrativeRouteRule() {
        ADMIN_ONLY_IN_THE_ADMIN_ROUTE_RULE.check(PRODUCTION);
    }

    @Test
    void everyAdministrativeOperationChecksTheRoleItself() {
        ADMIN_OPERATIONS_CHECK_THE_ROLE.check(PRODUCTION);
    }

    @Test
    void noClassOutsideTheAuditModuleDependsOnItsInternals() {
        AUDIT_INTERNALS_STAY_INSIDE_THE_MODULE.check(PRODUCTION);
    }

    @Test
    void theRetentionPoolIsInjectedOnlyIntoThePurge() {
        RETENTION_POOL_ONLY_IN_THE_PURGE.check(PRODUCTION);
    }

    @Test
    void theRulesDetectTheTestOnlyAdministratorContext() {
        // AdminSessions (test code) creates an ADMIN principal: exactly what production code must never do.
        JavaClasses testOnly = new ClassFileImporter().importClasses(com.memetrade.testsupport.AdminSessions.class);

        assertThatThrownBy(() -> ADMIN_ONLY_IN_THE_ADMIN_ROUTE_RULE.check(testOnly))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ADMIN");
        assertThatThrownBy(() -> PRINCIPAL_ONLY_FROM_THE_SESSION_PATH.check(testOnly))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Principal");
    }

    @Test
    void theRetentionRuleDetectsAnotherClassUsingThePool() throws ClassNotFoundException {
        // The purge integration test receives the pool directly: a class the production rule would refuse.
        JavaClasses testOnly =
                new ClassFileImporter().importClasses(Class.forName("com.memetrade.audit.infra.AuditRetentionPurgeIT"));

        assertThatThrownBy(() -> RETENTION_POOL_ONLY_IN_THE_PURGE.check(testOnly))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AuditRetentionDataSource");
    }
}
