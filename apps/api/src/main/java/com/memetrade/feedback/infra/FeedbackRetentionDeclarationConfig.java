package com.memetrade.feedback.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * J7 (BR2.17): the feedback purge ({@code feedbackPurge}, {@link FeedbackPurgeJob}) declares the period it applies,
 * {@code memetrade.admin.feedback.retention} from the creation of the comment.
 */
@Configuration(proxyBeanMethods = false)
public class FeedbackRetentionDeclarationConfig {

    @Bean
    public RetentionEnforcerDeclaration feedbackPurgeDeclaration(FeedbackProperties properties) {
        return RetentionEnforcerDeclaration.of(
                FeedbackPurgeJob.LOCK_NAME, RetentionTerm.of(properties.retention()), RetentionAnchor.CREATED);
    }
}
