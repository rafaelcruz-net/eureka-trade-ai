package com.memetrade.realexecution.infra;

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
 * 261113-trading-real-bot U6, Step 19 (review-03 R-12; tests written before the declarations): the daily purge of the REAL execution ({@code real-execution-purge}) declares itself
 * (J7), one declaration per table with its own period, so the REAL rows of the retention catalog of the Privacy module
 * hold against the age triggers of migration 0019. The selection of the job waits one day more than each period, only as a
 * margin over the trigger.
 */
class RealExecutionRetentionDeclarationConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RealExecutionRetentionDeclarationConfig.class);

    @Test
    void eachTableOfThePurgeDeclaresThePeriodAndAnchorOfItsTrigger() {
        runner.run(context -> {
            Map<String, RetentionEnforcerDeclaration> byPurge =
                    context.getBeansOfType(RetentionEnforcerDeclaration.class).values().stream()
                            .collect(Collectors.toMap(
                                    RetentionEnforcerDeclaration::purgeId, declaration -> declaration));
            assertThat(byPurge.get("real-execution-purge/orders"))
                    .as("real-execution-purge/orders")
                    .isNotNull();
            assertThat(byPurge.get("real-execution-purge/orders").retention()).isEqualTo(RetentionTerm.months(60));
            assertThat(byPurge.get("real-execution-purge/orders").anchor()).isEqualTo(RetentionAnchor.CREATED);
            assertThat(byPurge.get("real-execution-purge/decisions"))
                    .as("real-execution-purge/decisions")
                    .isNotNull();
            assertThat(byPurge.get("real-execution-purge/decisions").retention())
                    .isEqualTo(RetentionTerm.months(13));
            assertThat(byPurge.get("real-execution-purge/decisions").anchor()).isEqualTo(RetentionAnchor.CONCLUDED);
        });
    }

    @Test
    void theSelectionsOfThePurgeWaitOneDayMoreThanTheDeclaredPeriods() {
        assertThat(RealExecutionPurgeJob.RECORDS).isEqualTo("5 years 1 day");
        assertThat(RealExecutionPurgeJob.DECISIONS).isEqualTo("13 months 1 day");
        assertThat(RealExecutionRetentionDeclarationConfig.RECORDS).isEqualTo(RetentionTerm.months(60));
        assertThat(RealExecutionRetentionDeclarationConfig.DECISIONS).isEqualTo(RetentionTerm.months(13));
    }

    @Test
    void theDeclarationsAreNotBoundToAProcessRole() {
        assertThat(RealExecutionRetentionDeclarationConfig.class.isAnnotationPresent(ProcessRole.class))
                .isFalse();
        runner.withPropertyValues("memetrade.process=worker")
                .run(context -> assertThat(context.getBeansOfType(RetentionEnforcerDeclaration.class))
                        .hasSize(2));
    }
}
