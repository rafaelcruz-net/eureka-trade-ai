package com.memetrade.identity.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.domain.login.ChallengeService;
import com.memetrade.identity.domain.login.LoginChallengeRepository;
import com.memetrade.identity.domain.login.LoginSettings;
import com.memetrade.identity.domain.login.LoginVerificationService;
import com.memetrade.identity.domain.login.NonceGenerator;
import com.memetrade.identity.domain.session.AccessTokens;
import com.memetrade.identity.domain.session.AccountRepository;
import com.memetrade.identity.domain.session.RefreshTokens;
import com.memetrade.identity.domain.session.SessionRepository;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.session.SessionSettings;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the identity domain (plain classes) with its infrastructure. */
@Configuration
public class IdentityConfig {

    @Bean
    public LoginSettings loginSettings(IdentityProperties properties) {
        return new LoginSettings(
                properties.guestAppOrigin(),
                properties.chain(),
                properties.challengeTtl(),
                Set.copyOf(properties.adminWalletAddresses()));
    }

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    public NonceGenerator nonceGenerator(SecureRandom secureRandom) {
        return new NonceGenerator(secureRandom);
    }

    @Bean
    public Ed25519SignatureVerifier ed25519SignatureVerifier() {
        return new Ed25519SignatureVerifier();
    }

    @Bean
    public SessionSettings sessionSettings(IdentityProperties properties) {
        return new SessionSettings(properties.accessTokenTtl(), properties.refreshTtl(), properties.sessionMaxAge());
    }

    @Bean
    public RefreshTokens refreshTokens(SecureRandom secureRandom) {
        return new RefreshTokens(secureRandom);
    }

    @Bean
    public AccessTokens accessTokens(IdentityProperties properties) {
        return new HmacAccessTokens(properties.accessTokenSigningKey());
    }

    @Bean
    public SessionService sessionService(
            SessionRepository sessions,
            AccessTokens accessTokens,
            RefreshTokens refreshTokens,
            SessionSettings settings,
            AuditEventPublisher audit,
            Clock clock) {
        return new SessionService(sessions, accessTokens, refreshTokens, settings, audit, clock);
    }

    @Bean
    public LoginVerificationService loginVerificationService(
            LoginChallengeRepository challenges,
            AccountRepository accounts,
            SessionService sessions,
            Ed25519SignatureVerifier signatures,
            AuditEventPublisher audit,
            LoginSettings settings,
            Clock clock) {
        return new LoginVerificationService(challenges, accounts, sessions, signatures, audit, settings, clock);
    }

    @Bean
    public ChallengeService challengeService(
            LoginChallengeRepository repository,
            NonceGenerator nonceGenerator,
            IdempotencyGuard idempotency,
            LoginSettings settings,
            Clock clock,
            ObjectMapper objectMapper) {
        return new ChallengeService(repository, nonceGenerator, idempotency, settings, clock, objectMapper);
    }
}
