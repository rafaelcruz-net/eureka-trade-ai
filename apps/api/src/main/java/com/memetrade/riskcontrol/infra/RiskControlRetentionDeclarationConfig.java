package com.memetrade.riskcontrol.infra;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * J7 (BR2.17): the purge of the approved evaluations ({@code riskcontrol-purge}) declares the period it applies:
 * {@code memetrade.riskcontrol.purge.trigger-age}, the age the database trigger of migration 0009 enforces before any
 * deletion (30 days from the evaluation). The selection waits one more day ({@code selection-age}, 31 days) only as a
 * safety margin over that trigger; the retention is the trigger's.
 */
@Configuration(proxyBeanMethods = false)
public class RiskControlRetentionDeclarationConfig {

    @Bean
    public RetentionEnforcerDeclaration riskcontrolPurgeDeclaration(RiskControlProperties properties) {
        return RetentionEnforcerDeclaration.of(
                "riskcontrol-purge", RetentionTerm.of(properties.purge().triggerAge()), RetentionAnchor.CREATED);
    }
}
