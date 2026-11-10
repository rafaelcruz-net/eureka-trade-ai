package com.memetrade.audit.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * J7 (BR2.17): the two purges of the Audit module declare the period they apply, read from {@code memetrade.audit.jobs}:
 * the trail ({@code auditRetention}, {@code retention-months} from the occurrence) and the delivered outbox rows
 * ({@code auditOutboxCleanup}, {@code outbox-delivered-retention} from the delivery).
 */
@Configuration(proxyBeanMethods = false)
public class AuditRetentionDeclarationConfig {

    @Bean
    public RetentionEnforcerDeclaration auditRetentionDeclaration(AuditProperties properties) {
        return RetentionEnforcerDeclaration.of(
                "auditRetention", RetentionTerm.months(properties.jobs().retentionMonths()), RetentionAnchor.CREATED);
    }

    @Bean
    public RetentionEnforcerDeclaration auditOutboxCleanupDeclaration(AuditProperties properties) {
        return RetentionEnforcerDeclaration.of(
                "auditOutboxCleanup",
                RetentionTerm.of(properties.jobs().outboxDeliveredRetention()),
                RetentionAnchor.DELIVERED);
    }
}
