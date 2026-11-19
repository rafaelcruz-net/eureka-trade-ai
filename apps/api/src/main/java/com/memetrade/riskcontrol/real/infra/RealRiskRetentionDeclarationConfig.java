package com.memetrade.riskcontrol.real.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 261113-trading-real-bot U3, loop-back 1 of Build and Test (U3-NFR3.7, J7): the daily purge of the REAL barrier
 * ({@code real-risk-purge}) declares the period it applies, 13 months from the writing of the row (the evaluation, the
 * reservation or the daily photo), the age the trigger of migration 0018 enforces before any deletion. The selection of
 * the job waits one more day only as a margin over that trigger; the retention is the trigger's. The purge runs in the
 * api; the declaration exists in every process (no {@code @ProcessRole}), as the other declarations.
 */
@Configuration(proxyBeanMethods = false)
public class RealRiskRetentionDeclarationConfig {

    @Bean
    public RetentionEnforcerDeclaration realRiskPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(
                RealRiskPurgeJob.LOCK_NAME, RealRiskPurgeJob.RETENTION, RetentionAnchor.CREATED);
    }
}
