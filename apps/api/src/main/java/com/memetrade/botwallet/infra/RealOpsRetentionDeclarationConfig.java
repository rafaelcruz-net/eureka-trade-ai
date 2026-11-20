package com.memetrade.botwallet.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 261113-trading-real-bot U6, Step 19 (review-03 R-12, J7): the daily purge of the REAL operations ({@link
 * RealOpsPurgeJob}, lock {@code real-ops-purge}) deletes four tables with two periods, so it declares one period per
 * table under {@code real-ops-purge/<table>}: the ages the triggers of migration 0021 enforce before any deletion
 * (releases 5 years after the revocation, reconciliations 13 months after the start, divergences 5 years after the
 * resolution, commands to the TxSigner 13 months after the confirmation). The selections of the job wait one day more
 * only as a margin over those triggers. The declarations exist in every process, as the other declarations.
 */
@Configuration(proxyBeanMethods = false)
public class RealOpsRetentionDeclarationConfig {

    /** The age of the short tables (reconciliations, commands): trigger {@code 13 months}. */
    static final RetentionTerm SHORT = RetentionTerm.months(13);

    /** The age of the long tables (releases, divergences): trigger {@code 5 years}. */
    static final RetentionTerm LONG = RetentionTerm.months(60);

    static final String PREFIX = RealOpsPurgeJob.LOCK_NAME + "/";

    @Bean
    public RetentionEnforcerDeclaration realOpsReleasesPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(PREFIX + "releases", LONG, RetentionAnchor.ENDED);
    }

    @Bean
    public RetentionEnforcerDeclaration realOpsReconciliationsPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(PREFIX + "reconciliations", SHORT, RetentionAnchor.CREATED);
    }

    @Bean
    public RetentionEnforcerDeclaration realOpsDivergencesPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(PREFIX + "divergences", LONG, RetentionAnchor.CONCLUDED);
    }

    // Named without "signer": CycleIT asserts that no bean of the monolith carries it in its name.
    @Bean
    public RetentionEnforcerDeclaration realOpsSigningServiceCommandsPurgeDeclaration() {
        return RetentionEnforcerDeclaration.of(PREFIX + "txsigner-commands", SHORT, RetentionAnchor.CONCLUDED);
    }
}
