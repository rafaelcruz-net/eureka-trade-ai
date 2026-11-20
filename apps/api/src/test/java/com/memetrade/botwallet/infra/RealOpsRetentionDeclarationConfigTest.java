package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 261113-trading-real-bot U6, Step 19 (review-03 R-12; tests written before the declarations): the daily purge of the REAL operations ({@code real-ops-purge}) declares itself
 * (J7), one declaration per table with its own period, so the REAL rows of the retention catalog of the Privacy module
 * hold against the age triggers of migration 0021. The selection of the job waits one day more than each period, only as a
 * margin over the trigger.
 */
class RealOpsRetentionDeclarationConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RealOpsRetentionDeclarationConfig.class);

    @Test
    void eachTableOfThePurgeDeclaresThePeriodAndAnchorOfItsTrigger() {
        runner.run(context -> {
            Map<String, RetentionEnforcerDeclaration> byPurge =
                    context.getBeansOfType(RetentionEnforcerDeclaration.class).values().stream()
                            .collect(Collectors.toMap(
                                    RetentionEnforcerDeclaration::purgeId, declaration -> declaration));
            assertThat(byPurge.get("real-ops-purge/releases"))
                    .as("real-ops-purge/releases")
                    .isNotNull();
            assertThat(byPurge.get("real-ops-purge/releases").retention()).isEqualTo(RetentionTerm.months(60));
            assertThat(byPurge.get("real-ops-purge/releases").anchor()).isEqualTo(RetentionAnchor.ENDED);
            assertThat(byPurge.get("real-ops-purge/reconciliations"))
                    .as("real-ops-purge/reconciliations")
                    .isNotNull();
            assertThat(byPurge.get("real-ops-purge/reconciliations").retention())
                    .isEqualTo(RetentionTerm.months(13));
            assertThat(byPurge.get("real-ops-purge/reconciliations").anchor()).isEqualTo(RetentionAnchor.CREATED);
            assertThat(byPurge.get("real-ops-purge/divergences"))
                    .as("real-ops-purge/divergences")
                    .isNotNull();
            assertThat(byPurge.get("real-ops-purge/divergences").retention()).isEqualTo(RetentionTerm.months(60));
            assertThat(byPurge.get("real-ops-purge/divergences").anchor()).isEqualTo(RetentionAnchor.CONCLUDED);
            assertThat(byPurge.get("real-ops-purge/txsigner-commands"))
                    .as("real-ops-purge/txsigner-commands")
                    .isNotNull();
            assertThat(byPurge.get("real-ops-purge/txsigner-commands").retention())
                    .isEqualTo(RetentionTerm.months(13));
            assertThat(byPurge.get("real-ops-purge/txsigner-commands").anchor()).isEqualTo(RetentionAnchor.CONCLUDED);
        });
    }

    @Test
    void theSelectionsOfThePurgeWaitOneDayMoreThanTheDeclaredPeriods() {
        assertThat(RealOpsPurgeJob.SHORT_SELECTION).isEqualTo("13 months 1 day");
        assertThat(RealOpsPurgeJob.LONG_SELECTION).isEqualTo("5 years 1 day");
        assertThat(RealOpsRetentionDeclarationConfig.SHORT).isEqualTo(RetentionTerm.months(13));
        assertThat(RealOpsRetentionDeclarationConfig.LONG).isEqualTo(RetentionTerm.months(60));
    }

    @Test
    void theDeclarationsAreNotBoundToAProcessRole() {
        assertThat(RealOpsRetentionDeclarationConfig.class.isAnnotationPresent(ProcessRole.class))
                .isFalse();
        runner.withPropertyValues("memetrade.process=worker")
                .run(context -> assertThat(context.getBeansOfType(RetentionEnforcerDeclaration.class))
                        .hasSize(4));
    }
}
