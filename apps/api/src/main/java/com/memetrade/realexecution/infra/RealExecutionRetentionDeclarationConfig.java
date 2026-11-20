package com.memetrade.realexecution.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 261113-trading-real-bot U6, Step 19 (review-03 R-12, J7): the daily purge of the REAL execution ({@link
 * RealExecutionPurgeJob}, lock {@code real-execution-purge}) declares its two periods: the orders, their sends and the
 * liquidations 5 years after they were written, and the closed decision requests 13 months after they were closed, the
 * ages the triggers of migration 0019 enforce before any deletion. The selections of the job wait one day more only as a
 * margin. The declarations exist in every process, as the other declarations.
 */
@Configuration(proxyBeanMethods = false)
public class RealExecutionRetentionDeclarationConfig {

    /** Orders, sends and liquidations: trigger {@code 5 years}. */
    static final RetentionTerm RECORDS = RetentionTerm.months(60);

    /** Closed decision requests: trigger {@code 13 months} from {@code processed_at}. */
    static final RetentionTerm DECISIONS = RetentionTerm.months(13);

    @Bean
    public RetentionEnforcerDeclaration realExecutionOrdersPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(
                RealExecutionPurgeJob.LOCK_NAME + "/orders", RECORDS, RetentionAnchor.CREATED);
    }

    @Bean
    public RetentionEnforcerDeclaration realExecutionDecisionsPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(
                RealExecutionPurgeJob.LOCK_NAME + "/decisions", DECISIONS, RetentionAnchor.CONCLUDED);
    }
}
