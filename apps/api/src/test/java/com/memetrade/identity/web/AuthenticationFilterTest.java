package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.domain.session.Account;
import com.memetrade.identity.domain.session.FakeAccessTokens;
import com.memetrade.identity.domain.session.InMemorySessionRepository;
import com.memetrade.identity.domain.session.OpenedSession;
import com.memetrade.identity.domain.session.RefreshTokens;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.session.SessionSettings;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("u1")
class AuthenticationFilterTest {

    private final MutableClock clock = new MutableClock();
    private final SessionService sessions = new SessionService(
            new InMemorySessionRepository(),
            new FakeAccessTokens(),
            new RefreshTokens(new SecureRandom()),
            new SessionSettings(Duration.ofMinutes(15), Duration.ofHours(8), Duration.ofHours(12)),
            new RecordingAuditPublisher(),
            clock);
    private final AuthenticationFilter filter =
            new AuthenticationFilter(sessions, new ProblemWriter(new ObjectMapper()));
    private final RequestAuthContext context = new RequestAuthContext();

    /** U3 Passo 9 (security-design.md): the invitation lookup is public only for GET on exactly one segment. */
    @Test
    @Tag("u3")
    void onlyAGetOnOneSegmentUnderInvitesPassesWithoutToken() throws Exception {
        String code = "A".repeat(43);
        MockHttpServletRequest lookup = new MockHttpServletRequest("GET", "/api/v1/invites/" + code);
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(lookup, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                reached.set(true);
            }
        }));

        assertThat(reached.get()).isTrue();
        assertThat(AuthenticationFilter.isInvitationLookup("GET", "/api/v1/invites/x"))
                .isTrue();
        assertThat(AuthenticationFilter.isInvitationLookup("GET", "/api/v1/invites/" + "x".repeat(128)))
                .isTrue();
        assertThat(AuthenticationFilter.isInvitationLookup("GET", "/api/v1/invites/" + "x".repeat(129)))
                .isFalse();
        assertThat(AuthenticationFilter.isInvitationLookup("POST", "/api/v1/invites/x"))
                .isFalse();
        assertThat(AuthenticationFilter.isInvitationLookup("DELETE", "/api/v1/invites/x"))
                .isFalse();
        assertThat(AuthenticationFilter.isInvitationLookup("GET", "/api/v1/invites/x/y"))
                .isFalse();
        assertThat(AuthenticationFilter.isInvitationLookup("GET", "/api/v1/invites/"))
                .isFalse();
        assertThat(AuthenticationFilter.isInvitationLookup("GET", "/api/v1/invites"))
                .isFalse();
        assertThat(AuthenticationFilter.isInvitationLookup("GET", "/api/v1/invitesx/y"))
                .isFalse();
        assertThat(run(new MockHttpServletRequest("GET", "/api/v1/invites/a/b")).getStatus())
                .isEqualTo(401);
        assertThat(run(new MockHttpServletRequest("PUT", "/api/v1/invites/" + code))
                        .getStatus())
                .isEqualTo(401);
        assertThat(run(new MockHttpServletRequest("GET", "/api/v1/invites/a%2Fb"))
                        .getStatus())
                .as("an encoded slash is decoded into a second segment")
                .isEqualTo(401);
    }

    @Test
    void aProtectedRouteWithoutBearerTokenIs401() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/v1/scanner/tokens"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    void anotherSchemeOrAnUnknownTokenIs401() throws Exception {
        MockHttpServletRequest basic = new MockHttpServletRequest("GET", "/api/v1/scanner/tokens");
        basic.addHeader("Authorization", "Basic abc");
        MockHttpServletRequest unknown = new MockHttpServletRequest("GET", "/api/v1/scanner/tokens");
        unknown.addHeader("Authorization", "Bearer forged");

        assertThat(run(basic).getStatus()).isEqualTo(401);
        assertThat(run(unknown).getStatus()).isEqualTo(401);
    }

    @Test
    void aValidTokenBindsThePrincipalOnlyDuringTheRequest() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        OpenedSession opened = sessions.open(new Account(user, tenant, Role.BETA_USER, true, true), clock.instant());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/scanner/tokens");
        request.addHeader("Authorization", "Bearer " + opened.accessToken());
        AtomicReference<Principal> seen = new AtomicReference<>();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                seen.set(context.current());
            }
        }));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(new Principal(tenant, user, Role.BETA_USER));
        assertThat(context.optional()).isEmpty();
        assertThatThrownBy(context::current).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void publicAuthRoutesPreflightAndNonApiPathsAreNotFiltered() throws Exception {
        for (String path : AuthenticationFilter.PUBLIC_ROUTES) {
            assertThat(run(new MockHttpServletRequest("POST", path)).getStatus())
                    .as(path)
                    .isEqualTo(200);
        }
        assertThat(run(new MockHttpServletRequest("OPTIONS", "/api/v1/scanner/tokens"))
                        .getStatus())
                .isEqualTo(200);
        assertThat(run(new MockHttpServletRequest("GET", "/v3/api-docs")).getStatus())
                .isEqualTo(200);
        assertThat(run(new MockHttpServletRequest("POST", "/api/v1/auth/logout"))
                        .getStatus())
                .isEqualTo(401);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
