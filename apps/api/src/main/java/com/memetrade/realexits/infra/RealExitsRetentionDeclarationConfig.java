package com.memetrade.realexits.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 261113-trading-real-bot U6, Step 19 (review-03 R-12, J7): the daily purge of the REAL exits ({@link
 * RealExitsPurgeJob}, lock {@code real-exits-purge}) declares the period it applies: the conditional exits 5 years after
 * they became final, the age the trigger of migration 0022 enforces before any deletion. The selection of the job waits
 * one day more only as a margin. The declaration exists in every process, as the other declarations.
 */
@Configuration(proxyBeanMethods = false)
public class RealExitsRetentionDeclarationConfig {

    /** Final conditional exits: trigger {@code 5 years} from {@code final_at}. */
    static final RetentionTerm RETENTION = RetentionTerm.months(60);

    @Bean
    public RetentionEnforcerDeclaration realExitsPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(RealExitsPurgeJob.LOCK_NAME, RETENTION, RetentionAnchor.ENDED);
    }
}
