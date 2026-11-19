package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 261113-trading-real-bot U3, loop-back 1 of Build and Test (U3-NFR3.7; tests written before the declaration): the daily
 * purge of the REAL barrier declares itself (J7) under its lock name, with the 13 months from the writing of the row that
 * the trigger of migration 0018 enforces, so the retention catalog of the Privacy module can hold it. The declaration
 * exists in every process, as the other declarations, because the policy table is served by the api.
 */
class RealRiskRetentionDeclarationConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RealRiskRetentionDeclarationConfig.class);

    @Test
    void thePurgeOfTheRealBarrierDeclaresThirteenMonthsFromTheRowUnderItsLockName() {
        runner.run(context -> {
            RetentionEnforcerDeclaration declaration = context.getBean(RetentionEnforcerDeclaration.class);
            assertThat(declaration.purgeId()).isEqualTo("real-risk-purge").isEqualTo(RealRiskPurgeJob.LOCK_NAME);
            assertThat(declaration.retention()).isEqualTo(RetentionTerm.months(13));
            assertThat(declaration.anchor()).isEqualTo(RetentionAnchor.CREATED);
        });
    }

    @Test
    void theSelectionOfThePurgeWaitsOneDayMoreThanTheDeclaredRetention() {
        assertThat(RealRiskPurgeJob.SELECTION).isEqualTo("13 months 1 day");
    }

    @Test
    void theDeclarationIsNotBoundToAProcessRole() {
        assertThat(RealRiskRetentionDeclarationConfig.class.isAnnotationPresent(ProcessRole.class))
                .isFalse();
        runner.withPropertyValues("memetrade.process=worker")
                .run(context -> assertThat(context).hasBean("realRiskPurgeDeclaration"));
    }
}
