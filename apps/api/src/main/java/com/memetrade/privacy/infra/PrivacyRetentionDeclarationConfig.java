package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * J7 (BR4.15, NFR7.1): the purge of the data subject requests declares the period it applies,
 * {@code memetrade.privacy.retention-months} (12) from the conclusion of the request. The row
 * {@code DATA_SUBJECT_REQUESTS} of the retention catalog becomes {@code PURGE dataSubjectRequestPurge} and leaves the
 * pending list in the same change.
 */
@Configuration(proxyBeanMethods = false)
public class PrivacyRetentionDeclarationConfig {

    @Bean
    public RetentionEnforcerDeclaration dataSubjectRequestPurgeDeclaration(PrivacyProperties properties) {
        return RetentionEnforcerDeclaration.of(
                PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE.purgeId(),
                RetentionTerm.months(properties.retentionMonths()),
                RetentionAnchor.CONCLUDED);
    }
}
