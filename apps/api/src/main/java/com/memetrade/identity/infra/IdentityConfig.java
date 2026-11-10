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

    /**
     * U3 (NFR10.3) and u1-legal-documents (BR1.1): read once at start-up with the injectable clock and the legal
     * settings; an invalid catalog or one without an activatable version stops the api with a clear message.
     */
    @Bean
    public TermsCatalog termsCatalog(
            ResourceLoader resourceLoader, TermsProperties properties, LegalProperties legal, Clock clock) {
        return new TermsCatalog(
                new PathMatchingResourcePatternResolver(resourceLoader), properties, legal.settings(), clock);
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
    public AccountQueryService accountQueryService(
            AccountDetailsRepository accounts,
            CurrentTerms terms,
            com.memetrade.identity.domain.terms.TermsAcceptanceLookup acceptances) {
        return new AccountQueryService(accounts, terms, acceptances);
    }

    /** u3-reacceptance (L2, BR3.4 to BR3.6): the acceptance of the current terms, only in the api process. */
    @Bean
    @com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
    public com.memetrade.identity.domain.terms.TermsAcceptanceService termsAcceptanceService(
            com.memetrade.identity.domain.terms.TermsAcceptanceStore store,
            CurrentTerms terms,
            IdempotencyGuard idempotency,
            AuditEventPublisher audit,
            com.memetrade.identity.domain.terms.TermsEvents events,
            Clock clock) {
        return new com.memetrade.identity.domain.terms.TermsAcceptanceService(
                store, terms, idempotency, audit, events, clock);
    }

    /** C17: the administration of invitations used by the administrative routes and by U8. */
    @Bean
    public IdentityAdministration identityAdministration(
            InviteService invites, AdminAccess admins, LoginSettings settings, IdentityDirectory directory) {
        return new IdentityAdministrationImpl(invites, admins, settings, directory);
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

    /**
     * U5 (NFR10.9): the signing key reaches only the api process; the session and login services built on it exist
     * only there, so the worker starts without {@code ACCESS_TOKEN_SIGNING_KEY}.
     */
    @Bean
    @com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
    public AccessTokens accessTokens(IdentityProperties properties) {
        return new HmacAccessTokens(properties.accessTokenSigningKey());
    }

    @Bean
    @com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
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
    @com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
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
    @com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
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

    /** U8: the administrator wallets of the configuration (BR1.1, BR1.4). */
    @Bean
    public com.memetrade.identity.domain.login.AdminWallets adminWallets(LoginSettings settings) {
        return com.memetrade.identity.domain.login.AdminWallets.of(settings.adminWalletAddresses());
    }

    /** U8: origin of the administrator app (its domain is the scope of the administrator challenge) and lifetimes. */
    @Bean
    public com.memetrade.identity.domain.login.AdminLoginSettings adminLoginSettings(
            @org.springframework.beans.factory.annotation.Value("${memetrade.admin.app-origin}")
                    java.net.URI adminAppOrigin,
            AdminLoginProperties properties) {
        return new com.memetrade.identity.domain.login.AdminLoginSettings(
                adminAppOrigin,
                properties.accessTokenTtl(),
                properties.sessionMaxAge(),
                com.memetrade.identity.domain.login.AdminLoginSettings.SYSTEM_TENANT);
    }

    /** U8 (BR1.1 to BR1.6): the administrator entry, only in the api process. */
    @Bean
    @com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
    public com.memetrade.identity.domain.login.AdminLoginService adminLoginService(
            LoginChallengeRepository challenges,
            AccountRepository accounts,
            com.memetrade.identity.domain.login.AdminAccounts adminAccounts,
            IdentityDirectory directory,
            SessionService sessions,
            Ed25519SignatureVerifier signatures,
            AuditEventPublisher audit,
            com.memetrade.identity.domain.login.AdminWallets wallets,
            com.memetrade.identity.domain.login.AdminLoginSettings settings,
            Clock clock) {
        return new com.memetrade.identity.domain.login.AdminLoginService(
                challenges, accounts, adminAccounts, directory, sessions, signatures, audit, wallets, settings, clock);
    }

    @Bean
    @com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
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
