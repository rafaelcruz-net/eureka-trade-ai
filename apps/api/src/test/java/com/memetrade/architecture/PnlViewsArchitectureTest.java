package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.architecture.othertenant.AskingOtherTenantsSample;
import com.memetrade.architecture.othertenant.AskingThroughTheImplementationSample;
import com.memetrade.bots.application.BotDirectoryImpl;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.web.PnlController;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 10 (decision F-2 of the NFR Design; NFR3.13, NFR3.17): the one read outside the
 * tenant of the caller ({@code BotDirectory.ownedByAnotherTenant}, a boolean by the primary key) is asked only by the
 * path of the 404 of the reads of the P&L ({@code PnlController}); its implementation reaches the repository only
 * through the adapter of the directory. The rule fails on a sample class that asks it from anywhere else.
 */
class PnlViewsArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    private static final JavaClasses SAMPLE = new ClassFileImporter().importClasses(AskingOtherTenantsSample.class);

    private static final JavaClasses SAMPLE_THROUGH_THE_IMPLEMENTATION =
            new ClassFileImporter().importClasses(AskingThroughTheImplementationSample.class);

    /**
     * A call of {@code ownedByAnotherTenant} on the port OR on any type that implements it (the concrete adapter
     * {@code BotDirectoryImpl} included): the owner of the target only has to be assignable to {@link BotDirectory}.
     */
    private static final DescribedPredicate<JavaMethodCall> ASKS_OTHER_TENANTS = DescribedPredicate.describe(
            "ownedByAnotherTenant(UUID, UUID) on BotDirectory or one of its implementations",
            call -> call.getName().equals("ownedByAnotherTenant")
                    && call.getTargetOwner().isAssignableTo(BotDirectory.class));

    /** Only the path of the 404 of the P&L asks whether a bot belongs to another tenant. */
    static final ArchRule ONLY_THE_404_PATH_ASKS_OTHER_TENANTS = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(PnlController.class.getName())
            .should()
            .callMethodWhere(ASKS_OTHER_TENANTS)
            .because("the only read outside the tenant answers only the 404 of the P&L (F-2, NFR3.17)");

    /** The query of the repository is reached only by the adapter of the directory. */
    static final ArchRule ONLY_THE_DIRECTORY_RUNS_THE_QUERY = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(BotDirectoryImpl.class.getName())
            .should()
            .callMethod(JdbcBotRepository.class, "ownedByAnotherTenant", UUID.class, UUID.class)
            .because("the read outside the tenant has one entrance, the port of the P&L (F-2)");

    @Test
    void onlyThe404PathAsksOtherTenants() {
        ONLY_THE_404_PATH_ASKS_OTHER_TENANTS.check(PRODUCTION);
        ONLY_THE_DIRECTORY_RUNS_THE_QUERY.check(PRODUCTION);
    }

    @Test
    void theRuleFailsOnAClassThatAsksFromAnywhereElse() {
        assertThatThrownBy(() -> ONLY_THE_404_PATH_ASKS_OTHER_TENANTS.check(SAMPLE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(AskingOtherTenantsSample.class.getName());
    }

    @Test
    void theRuleFailsOnAClassThatAsksThroughTheConcreteAdapter() {
        assertThatThrownBy(() -> ONLY_THE_404_PATH_ASKS_OTHER_TENANTS.check(SAMPLE_THROUGH_THE_IMPLEMENTATION))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(AskingThroughTheImplementationSample.class.getName());
    }
}
