package com.memetrade.bots.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * J7 (BR2.17): the purge of the HOLD and NO_TRADE decisions ({@code decisionPurge}) declares the period it applies,
 * {@code memetrade.bots.decision-retention} from the decision. The purge runs in the worker; the declaration exists in
 * both processes (no {@code @ProcessRole}), so the api context, where the retention table is served, has it too.
 */
@Configuration(proxyBeanMethods = false)
public class BotsRetentionDeclarationConfig {

    @Bean
    public RetentionEnforcerDeclaration decisionPurgeDeclaration(BotProperties properties) {
        return RetentionEnforcerDeclaration.of(
                "decisionPurge", RetentionTerm.of(properties.decisionRetention()), RetentionAnchor.CREATED);
    }
}
