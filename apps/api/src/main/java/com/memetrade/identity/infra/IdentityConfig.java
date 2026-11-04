package com.memetrade.identity.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.domain.account.AccountDetailsRepository;
import com.memetrade.identity.domain.account.AccountQueryService;
import com.memetrade.identity.domain.account.GuestAccountRepository;
import com.memetrade.identity.domain.invite.IdentityDirectory;
import com.memetrade.identity.domain.invite.InviteCodes;
import com.memetrade.identity.domain.invite.InviteLookupService;
import com.memetrade.identity.domain.invite.InviteRepository;
import com.memetrade.identity.domain.invite.InviteService;
import com.memetrade.identity.domain.login.ChallengeService;
import com.memetrade.identity.domain.login.InviteAccountCreation;
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
import com.memetrade.identity.domain.terms.CurrentTerms;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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

    /** U3 (NFR10.3): read once at start-up; a missing or invalid terms folder stops the api with a clear message. */
    @Bean
    public TermsCatalog termsCatalog(ResourceLoader resourceLoader, TermsProperties properties) {
        return new TermsCatalog(new PathMatchingResourcePatternResolver(resourceLoader), properties);
    }

    @Bean
    public InviteCodes inviteCodes(SecureRandom secureRandom) {
        return new InviteCodes(secureRandom);
    }

    @Bean
    public InviteService inviteService(
            InviteRepository invites,
            IdentityDirectory directory,
            InviteCodes codes,
            AuditEventPublisher audit,
            Clock clock) {
        return new InviteService(invites, directory, codes, audit, clock);
    }

    @Bean
    public InviteLookupService inviteLookupService(InviteRepository invites, CurrentTerms terms, Clock clock) {
        return new InviteLookupService(invites, terms, clock);
    }

    @Bean
    public AccountQueryService accountQueryService(AccountDetailsRepository accounts, CurrentTerms terms) {
        return new AccountQueryService(accounts, terms);
    }

    /** C17: the administration of invitations used by the administrative routes and by U8. */
    @Bean
    public IdentityAdministration identityAdministration(
            InviteService invites, AdminAccess admins, LoginSettings settings) {
        return new IdentityAdministrationImpl(invites, admins, settings);
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
            Clock clock,
            InviteAccountCreation invitations) {
        return new LoginVerificationService(
                challenges, accounts, sessions, signatures, audit, settings, clock, invitations);
    }

    /** U3 F5: account creation inside the verification transaction. */
    @Bean
    public InviteAccountCreation inviteAccountCreation(
            InviteRepository invites,
            IdentityDirectory directory,
            AccountRepository accounts,
            GuestAccountRepository guests,
            CurrentTerms terms,
            SessionService sessions,
            AuditEventPublisher audit,
            LoginSettings settings) {
        return new InviteAccountCreation(invites, directory, accounts, guests, terms, sessions, audit, settings);
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
