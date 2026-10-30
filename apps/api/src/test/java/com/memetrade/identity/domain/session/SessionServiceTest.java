package com.memetrade.identity.domain.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Role;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class SessionServiceTest {

    private final MutableClock clock = new MutableClock();
    private final InMemorySessionRepository repository = new InMemorySessionRepository();
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();
    private final SessionService service = new SessionService(
            repository,
            new FakeAccessTokens(),
            new RefreshTokens(new SecureRandom()),
            new SessionSettings(Duration.ofMinutes(15), Duration.ofHours(8), Duration.ofHours(12)),
            audit,
            clock);
    private final Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER, true, true);

    @Test
    void openingASessionSetsThe15Minute8HourAnd12HourDeadlines() {
        OpenedSession opened = service.open(account, clock.instant());

        assertThat(opened.accessTokenExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(opened.refreshExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(8)));
        assertThat(opened.sessionExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(12)));
        assertThat(opened.role()).isEqualTo(Role.BETA_USER);
    }

    @Test
    void onlyTheDigestOfTheRefreshTokenIsStored() {
        OpenedSession opened = service.open(account, clock.instant());

        Session stored = repository.findById(opened.sessionId()).orElseThrow();
        assertThat(stored.refreshTokenDigest())
                .isNotEqualTo(opened.refreshToken())
                .isEqualTo(RefreshTokens.digest(opened.refreshToken()))
                .hasSize(64);
        assertThat(opened.toString()).doesNotContain(opened.refreshToken()).doesNotContain(opened.accessToken());
    }

    @Test
    void aValidAccessTokenAuthenticatesTheTenantAndPerson() {
        OpenedSession opened = service.open(account, clock.instant());

        AuthenticatedSession authenticated =
                service.authenticate(opened.accessToken()).orElseThrow();

        assertThat(authenticated.principal().tenantId()).isEqualTo(account.tenantId());
        assertThat(authenticated.principal().userId()).isEqualTo(account.userId());
        assertThat(authenticated.sessionId()).isEqualTo(opened.sessionId());
    }

    @Test
    void theAccessTokenStopsWorkingAfter15Minutes() {
        OpenedSession opened = service.open(account, clock.instant());
        clock.advance(Duration.ofMinutes(15));

        assertThat(service.authenticate(opened.accessToken())).isEmpty();
    }

    @Test
    void aRevokedSessionIsRefusedEvenWithinTheTokens15Minutes() {
        OpenedSession opened = service.open(account, clock.instant());
        AuthenticatedSession authenticated =
                service.authenticate(opened.accessToken()).orElseThrow();

        service.logout(authenticated, "corr");
        clock.advance(Duration.ofMinutes(1));

        assertThat(service.authenticate(opened.accessToken())).isEmpty();
    }

    @Test
    void aDisabledUserOrSuspendedTenantIsRefusedOnTheNextRequest() {
        OpenedSession first = service.open(account, clock.instant());
        repository.setActivity(first.sessionId(), false, true);
        assertThat(service.authenticate(first.accessToken())).isEmpty();

        OpenedSession second = service.open(account, clock.instant());
        repository.setActivity(second.sessionId(), true, false);
        assertThat(service.authenticate(second.accessToken())).isEmpty();
    }

    @Test
    void refreshRotatesTheRefreshTokenAndExtendsItBy8Hours() {
        OpenedSession opened = service.open(account, clock.instant());
        clock.advance(Duration.ofHours(2));

        RefreshOutcome outcome = service.refresh(opened.refreshToken(), "corr");

        OpenedSession renewed = ((RefreshOutcome.Renewed) outcome).session();
        assertThat(renewed.refreshToken()).isNotEqualTo(opened.refreshToken());
        assertThat(renewed.refreshExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(8)));
        assertThat(renewed.sessionExpiresAt()).isEqualTo(opened.sessionExpiresAt());
        assertThat(service.authenticate(renewed.accessToken())).isPresent();
    }

    @Test
    void theRefreshExtensionNeverPassesThe12HourSessionLimit() {
        OpenedSession opened = service.open(account, clock.instant());
        clock.advance(Duration.ofHours(7));
        OpenedSession renewed = ((RefreshOutcome.Renewed) service.refresh(opened.refreshToken(), "c")).session();
        clock.advance(Duration.ofHours(4));

        OpenedSession capped = ((RefreshOutcome.Renewed) service.refresh(renewed.refreshToken(), "c")).session();

        assertThat(capped.refreshExpiresAt()).isEqualTo(opened.sessionExpiresAt());
        assertThat(capped.accessTokenExpiresAt()).isBeforeOrEqualTo(opened.sessionExpiresAt());
    }

    @Test
    void refreshAfter8IdleHoursOr12HoursExpiresTheSession() {
        OpenedSession idle = service.open(account, clock.instant());
        clock.advance(Duration.ofHours(8).plusSeconds(1));
        assertThat(refusal(service.refresh(idle.refreshToken(), "c"))).isEqualTo(RefreshOutcome.Reason.EXPIRED);
        assertThat(repository.findById(idle.sessionId()).orElseThrow().state()).isEqualTo(SessionState.EXPIRED);

        OpenedSession old = service.open(account, clock.instant());
        for (int hour = 0; hour < 12; hour += 6) {
            clock.advance(Duration.ofHours(6));
            if (hour == 0) {
                old = ((RefreshOutcome.Renewed) service.refresh(old.refreshToken(), "c")).session();
            }
        }
        clock.advance(Duration.ofSeconds(1));
        assertThat(refusal(service.refresh(old.refreshToken(), "c"))).isEqualTo(RefreshOutcome.Reason.EXPIRED);
    }

    @Test
    void reusingARefreshTokenRevokesTheWholeSessionAndIsAuditedOnce() {
        OpenedSession opened = service.open(account, clock.instant());
        OpenedSession renewed = ((RefreshOutcome.Renewed) service.refresh(opened.refreshToken(), "c")).session();

        assertThat(refusal(service.refresh(opened.refreshToken(), "c"))).isEqualTo(RefreshOutcome.Reason.REUSED);
        assertThat(refusal(service.refresh(opened.refreshToken(), "c"))).isEqualTo(RefreshOutcome.Reason.REUSED);

        assertThat(repository.findById(opened.sessionId()).orElseThrow().state())
                .isEqualTo(SessionState.REVOKED);
        assertThat(refusal(service.refresh(renewed.refreshToken(), "c"))).isEqualTo(RefreshOutcome.Reason.CLOSED);
        assertThat(service.authenticate(renewed.accessToken())).isEmpty();
        assertThat(audit.withAction(AuditActions.SESSION_REFRESH_REUSED)).hasSize(1);
    }

    @Test
    void refreshForADisabledAccountRevokesTheSession() {
        OpenedSession opened = service.open(account, clock.instant());
        repository.setActivity(opened.sessionId(), false, true);

        assertThat(refusal(service.refresh(opened.refreshToken(), "c")))
                .isEqualTo(RefreshOutcome.Reason.INACTIVE_ACCOUNT);
        assertThat(repository.findById(opened.sessionId()).orElseThrow().state())
                .isEqualTo(SessionState.REVOKED);
    }

    @Test
    void logoutRevokesAndAuditsOnlyTheFirstTime() {
        OpenedSession opened = service.open(account, clock.instant());
        AuthenticatedSession authenticated =
                service.authenticate(opened.accessToken()).orElseThrow();

        service.logout(authenticated, "c1");
        service.logout(authenticated, "c2");

        assertThat(repository.findById(opened.sessionId()).orElseThrow().state())
                .isEqualTo(SessionState.REVOKED);
        assertThat(audit.withAction(AuditActions.LOGOUT)).hasSize(1);
        assertThat(refusal(service.refresh(opened.refreshToken(), "c"))).isEqualTo(RefreshOutcome.Reason.CLOSED);
    }

    @Test
    void missingOrUnknownRefreshTokensAreRefused() {
        assertThat(refusal(service.refresh(null, "c"))).isEqualTo(RefreshOutcome.Reason.MISSING);
        assertThat(refusal(service.refresh(" ", "c"))).isEqualTo(RefreshOutcome.Reason.MISSING);
        assertThat(refusal(service.refresh("never-issued", "c"))).isEqualTo(RefreshOutcome.Reason.UNKNOWN);
        assertThat(service.authenticate(null)).isEmpty();
    }

    private static RefreshOutcome.Reason refusal(RefreshOutcome outcome) {
        assertThat(outcome).isInstanceOf(RefreshOutcome.Refused.class);
        return ((RefreshOutcome.Refused) outcome).reason();
    }
}
