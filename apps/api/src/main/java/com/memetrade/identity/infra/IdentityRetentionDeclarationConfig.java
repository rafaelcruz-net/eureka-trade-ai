package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * J7 (BR2.17): the three purges of the Identity module declare the period they apply, each read from its own property:
 * idempotency records ({@code idempotencyCleanup}, {@code memetrade.idempotency.default-retention} from the creation),
 * login challenges ({@code loginChallengePurge}, {@code challenge-retention} from the expiration) and sessions
 * ({@code sessionPurge}, {@code session-retention} from the end).
 */
@Configuration(proxyBeanMethods = false)
public class IdentityRetentionDeclarationConfig {

    @Bean
    public RetentionEnforcerDeclaration idempotencyCleanupDeclaration(IdempotencyProperties properties) {
        return RetentionEnforcerDeclaration.of(
                "idempotencyCleanup", RetentionTerm.of(properties.defaultRetention()), RetentionAnchor.CREATED);
    }

    @Bean
    public RetentionEnforcerDeclaration loginChallengePurgeDeclaration(IdentityPurgeProperties properties) {
        return RetentionEnforcerDeclaration.of(
                IdentityPurge.LOGIN_CHALLENGE_PURGE.purgeId(),
                RetentionTerm.of(properties.challengeRetention()),
                RetentionAnchor.EXPIRED);
    }

    @Bean
    public RetentionEnforcerDeclaration sessionPurgeDeclaration(IdentityPurgeProperties properties) {
        return RetentionEnforcerDeclaration.of(
                IdentityPurge.SESSION_PURGE.purgeId(),
                RetentionTerm.of(properties.sessionRetention()),
                RetentionAnchor.ENDED);
    }
}
