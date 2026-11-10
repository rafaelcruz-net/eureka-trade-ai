package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsAcceptanceLookup;
import com.memetrade.identity.domain.terms.TermsAcceptanceSource;
import com.memetrade.identity.domain.terms.TermsDocument;
import com.memetrade.identity.domain.terms.TermsEvents;
import com.memetrade.identity.infra.ReacceptanceProperties;
import com.memetrade.shared.web.ProblemWriter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 2 of u3-reacceptance, written BEFORE {@link TermsReacceptanceFilter} from the rules (BR3.1, BR3.2, BR3.3;
 * NFR2.2, NFR2.5; US4.1): a guest without an acceptance of the effective current version gets {@code 403
 * TERMS_REACCEPTANCE_REQUIRED} with {@code currentVersion} on every route outside the allowed prefixes, an account
 * without any acceptance too; a version already accepted passes even when it is not the latest acceptance; the allowed
 * prefixes, an administrator token and a request without a principal pass; a database failure is {@code 503} and the
 * request never goes on unchecked.
 */
@Tag("u3-reacceptance")
class TermsReacceptanceFilterTest {

    static final List<String> ALLOWED = List.of(
            "/api/v1/legal/",
            "/api/v1/terms/acceptances",
            "/api/v1/account",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh");

    private final ObjectMapper json = new ObjectMapper();
    private final FakeAuth auth = new FakeAuth();
    private final FakeTerms terms = new FakeTerms("v2");
    private final FakeLookup lookup = new FakeLookup();
    private final RecordingEvents events = new RecordingEvents();
    private final TermsReacceptanceFilter filter = new TermsReacceptanceFilter(
            auth, terms, lookup, new ReacceptanceProperties(ALLOWED), new ProblemWriter(json), events);

    private final Principal guest = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);

    @Test
    void aGuestWhoAcceptedOnlyAnEarlierVersionGets403WithTheCurrentVersion() throws Exception {
        auth.principal = Optional.of(guest);
        lookup.accept(guest, "v1");

        Outcome outcome = run("GET", "/api/v1/scanner/tokens");

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(403);
        assertThat(outcome.response().getContentType()).startsWith("application/problem+json");
        JsonNode problem = json.readTree(outcome.response().getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
        assertThat(problem.get("currentVersion").asText()).isEqualTo("v2");
        assertThat(problem.get("status").asInt()).isEqualTo(403);
        assertThat(problem.get("type").asText()).isEqualTo("https://memetrade.app/problems/terms");
        assertThat(problem.get("correlationId").asText()).isNotBlank();
        assertThat(events.blocked).isEqualTo(1);
    }

    @Test
    void anAccountWithoutAnyAcceptanceGets403Too() throws Exception {
        auth.principal = Optional.of(guest);

        Outcome outcome = run("GET", "/api/v1/pnl");

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(403);
        assertThat(json.readTree(outcome.response().getContentAsString())
                        .get("currentVersion")
                        .asText())
                .isEqualTo("v2");
    }

    @Test
    void aGuestWhoAcceptedTheCurrentVersionPassesAndTheCheckUsesThePersonAndTenantOfThePrincipal() throws Exception {
        auth.principal = Optional.of(guest);
        lookup.accept(guest, "v2");

        Outcome outcome = run("GET", "/api/v1/bots");

        assertThat(outcome.reached()).isTrue();
        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(lookup.asked).containsExactly(guest.userId() + "|" + guest.tenantId() + "|v2");
        assertThat(events.blocked).isZero();
    }

    /** BR3.1: the lock compares with every acceptance, not only the latest one. */
    @Test
    void whenTheCurrentVersionGoesBackToAnAlreadyAcceptedVersionTheGuestStillPasses() throws Exception {
        auth.principal = Optional.of(guest);
        lookup.accept(guest, "v1");
        lookup.accept(guest, "v2");
        terms.current = "v1";

        assertThat(run("GET", "/api/v1/ledger").reached()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/legal/current",
                "/api/v1/legal/versions/v1",
                "/api/v1/terms/acceptances",
                "/api/v1/account",
                "/api/v1/auth/logout",
                "/api/v1/auth/refresh"
            })
    void everyAllowedPrefixPassesWithoutAnyCheck(String path) throws Exception {
        auth.principal = Optional.of(guest);

        Outcome outcome = run("POST", path);

        assertThat(outcome.reached()).isTrue();
        assertThat(lookup.asked).isEmpty();
        assertThat(events.blocked).isZero();
    }

    /** A path that only shares the text of a prefix, or reaches another route through dot segments, is locked. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/accounting",
                "/api/v1/terms/acceptances-history",
                "/api/v1/account/../bots",
                "/api/v1/legal/../feedback",
                "//api/v1/bots"
            })
    void aPathThatOnlyLooksLikeAnAllowedPrefixIsLocked(String path) throws Exception {
        auth.principal = Optional.of(guest);

        Outcome outcome = run("GET", path);

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(403);
    }

    /** BR3.3: every bot route of the guest is locked until the acceptance. */
    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/bots", "/api/v1/bots/b1/start", "/api/v1/bots/b1/resume", "/api/v1/bots/b1/limits"})
    void theBotRoutesAreLocked(String path) throws Exception {
        auth.principal = Optional.of(guest);
        lookup.accept(guest, "v1");

        Outcome outcome = run("POST", path);

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(403);
    }

    @Test
    void anAdministratorTokenPassesWithoutAnyCheck() throws Exception {
        auth.principal = Optional.of(new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN));

        Outcome outcome = run("GET", "/api/v1/admin/system-status");

        assertThat(outcome.reached()).isTrue();
        assertThat(lookup.asked).isEmpty();
    }

    @Test
    void aRequestWithoutAPrincipalPassesToItsOwnRules() throws Exception {
        Outcome outcome = run("GET", "/api/v1/invites/abc");

        assertThat(outcome.reached()).isTrue();
        assertThat(lookup.asked).isEmpty();
    }

    @Test
    void aPreflightAndARouteOutsideTheApiAreNeverChecked() throws Exception {
        auth.principal = Optional.of(guest);

        assertThat(run("OPTIONS", "/api/v1/bots").reached()).isTrue();
        assertThat(run("GET", "/actuator/health").reached()).isTrue();
        assertThat(lookup.asked).isEmpty();
    }

    /** NFR2.5: with the database down the lock answers 503 itself and the request never goes on unchecked. */
    @Test
    void whenTheDatabaseIsUnavailableTheAnswerIs503AndTheRequestNeverGoesOn() throws Exception {
        auth.principal = Optional.of(guest);
        lookup.failure = new DataAccessResourceFailureException("connection refused to host db with secret detail");

        Outcome outcome = run("GET", "/api/v1/bots");

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(503);
        JsonNode problem = json.readTree(outcome.response().getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(outcome.response().getContentAsString()).doesNotContain("secret detail");
        assertThat(problem.has("currentVersion")).isFalse();
        assertThat(events.blocked).isZero();
    }

    /** Review finding: an unexpected state failure also answers 503 with an RFC 7807 body, never the container's 500. */
    @Test
    void anUnexpectedStateFailureIsAlso503WithAProblemBody() throws Exception {
        auth.principal = Optional.of(guest);
        lookup.failure = new IllegalStateException("no effective terms");

        Outcome outcome = run("GET", "/api/v1/bots");

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(503);
        assertThat(json.readTree(outcome.response().getContentAsString())
                        .get("code")
                        .asText())
                .isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void anyOtherDatabaseFailureIsAlso503() throws Exception {
        auth.principal = Optional.of(guest);
        lookup.failure = new CannotAcquireLockException("lock timeout");

        Outcome outcome = run("POST", "/api/v1/feedback");

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(503);
    }

    private Outcome run(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                reached.set(true);
                res.setStatus(200);
            }
        }));
        return new Outcome(reached.get(), response);
    }

    private record Outcome(boolean reached, MockHttpServletResponse response) {}

    static final class FakeAuth implements AuthContext {
        Optional<Principal> principal = Optional.empty();

        @Override
        public Principal current() {
            return principal.orElseThrow(UnauthenticatedException::new);
        }

        @Override
        public Optional<Principal> optional() {
            return principal;
        }
    }

    static final class FakeTerms implements CurrentTerms {
        String current;

        FakeTerms(String current) {
            this.current = current;
        }

        @Override
        public TermsDocument current() {
            return new TermsDocument(current, "terms " + current, "risk " + current);
        }

        @Override
        public Optional<TermsDocument> find(String version) {
            return Optional.of(new TermsDocument(version, "terms " + version, "risk " + version));
        }
    }

    static final class FakeLookup implements TermsAcceptanceLookup {
        final Set<String> accepted = new HashSet<>();
        final List<String> asked = new ArrayList<>();
        RuntimeException failure;

        void accept(Principal person, String version) {
            accepted.add(person.userId() + "|" + person.tenantId() + "|" + version);
        }

        @Override
        public boolean hasAccepted(UUID userId, UUID tenantId, String version) {
            String key = userId + "|" + tenantId + "|" + version;
            asked.add(key);
            if (failure != null) {
                throw failure;
            }
            return accepted.contains(key);
        }
    }

    static final class RecordingEvents implements TermsEvents {
        int blocked;
        final List<TermsAcceptanceSource> accepted = new ArrayList<>();

        @Override
        public void blocked() {
            blocked++;
        }

        @Override
        public void accepted(TermsAcceptanceSource source) {
            accepted.add(source);
        }
    }
}
