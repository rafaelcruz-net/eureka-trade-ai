package com.memetrade.testsupport;

import com.memetrade.audit.infra.AuditProperties;
import com.memetrade.audit.infra.AuditRetentionDeclarationConfig;
import com.memetrade.bots.infra.BotProperties;
import com.memetrade.bots.infra.BotsRetentionDeclarationConfig;
import com.memetrade.feedback.infra.FeedbackProperties;
import com.memetrade.feedback.infra.FeedbackRetentionDeclarationConfig;
import com.memetrade.identity.infra.IdempotencyProperties;
import com.memetrade.identity.infra.IdentityPurgeProperties;
import com.memetrade.identity.infra.IdentityRetentionDeclarationConfig;
import com.memetrade.riskcontrol.infra.RiskControlProperties;
import com.memetrade.riskcontrol.infra.RiskControlRetentionDeclarationConfig;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * u2-retention (BR2.17, J7): the retention declarations of every module, built by their own configurations from the
 * REAL {@code application.yml} (the properties each purge uses), without a database. Used by the declaration and the
 * conformance tests.
 */
public final class RetentionDeclarations {

    private RetentionDeclarations() {}

    /** A context with the configuration of the application and the declaration configurations of the five modules. */
    public static ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(
                        PurgeProperties.class,
                        AuditRetentionDeclarationConfig.class,
                        FeedbackRetentionDeclarationConfig.class,
                        BotsRetentionDeclarationConfig.class,
                        RiskControlRetentionDeclarationConfig.class,
                        IdentityRetentionDeclarationConfig.class,
                        // u4-data-requests (BR4.15): the purge of the closed data subject requests.
                        com.memetrade.privacy.infra.PrivacyRetentionDeclarationConfig.class,
                        // 261113-trading-real-bot U3 (U3-NFR3.7): the purge of the REAL barrier.
                        com.memetrade.riskcontrol.real.infra.RealRiskRetentionDeclarationConfig.class);
    }

    /** The declarations of {@link #context()}, in bean-name order. */
    public static List<RetentionEnforcerDeclaration> fromApplicationYml() {
        AtomicReference<List<RetentionEnforcerDeclaration>> declarations = new AtomicReference<>();
        context().run(context -> {
            if (context.getStartupFailure() != null) {
                throw new IllegalStateException("The declaration context failed", context.getStartupFailure());
            }
            declarations.set(new ArrayList<>(
                    context.getBeansOfType(RetentionEnforcerDeclaration.class).values()));
        });
        return List.copyOf(declarations.get());
    }

    /** The properties the purges read, bound from the application configuration. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        AuditProperties.class,
        FeedbackProperties.class,
        BotProperties.class,
        RiskControlProperties.class,
        IdempotencyProperties.class,
        IdentityPurgeProperties.class,
        com.memetrade.privacy.infra.PrivacyProperties.class
    })
    static class PurgeProperties {}
}
