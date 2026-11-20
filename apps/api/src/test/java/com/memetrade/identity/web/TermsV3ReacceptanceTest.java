package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.legal.LegalSettings;
import com.memetrade.identity.infra.ReacceptanceProperties;
import com.memetrade.identity.infra.TermsCatalog;
import com.memetrade.identity.infra.TermsProperties;
import com.memetrade.identity.web.TermsReacceptanceFilterTest.FakeAuth;
import com.memetrade.identity.web.TermsReacceptanceFilterTest.FakeLookup;
import com.memetrade.identity.web.TermsReacceptanceFilterTest.RecordingEvents;
import com.memetrade.shared.web.ProblemWriter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Step 19 of u6-real-modes-ui (AC1.1.1, FR9.2; team.md mandatory risk test "a versão nova dos termos exige novo aceite
 * ... exceto"): with the REAL catalog on the day v3 takes effect (2026-11-20), a person who accepted only v1 is locked
 * with {@code currentVersion=v3} on every guest route outside the allowed prefixes, the REAL routes included, and still
 * reads the bot wallet and withdraws; after the acceptance of v3 the same person passes. The legal review fields of v3
 * are internal: no answer of the legal documents carries them.
 */
@Tag("u1-legal-documents")
class TermsV3ReacceptanceTest {

    private static final Instant V3_DAY = Instant.parse("2026-11-20T12:00:00Z");
    private static final String WITHDRAWAL = "/api/v1/real/bot-wallet/withdrawals/0b8f0d4e-6d7e-4a43-9a3c-2a8f51f0c001";

    private final ObjectMapper json = new ObjectMapper();
    private final FakeAuth auth = new FakeAuth();
    private final FakeLookup lookup = new FakeLookup();
    private final TermsCatalog realCatalog = realCatalog(V3_DAY);
    private final TermsReacceptanceFilter filter = new TermsReacceptanceFilter(
            auth,
            realCatalog,
            lookup,
            new ReacceptanceProperties(TermsReacceptanceFilterTest.ALLOWED),
            new ProblemWriter(json),
            new RecordingEvents());
    private final Principal person = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);

    @ParameterizedTest(name = "{0} {1} is locked until v3 is accepted")
    @CsvSource({
        "GET,/api/v1/bots",
        "GET,/api/v1/scanner/tokens",
        "GET,/api/v1/pnl",
        "POST,/api/v1/real/bot-wallet",
        "PUT,/api/v1/real/mode",
        "GET,/api/v1/real/orders",
        "GET,/api/v1/real/bot-wallet/deposits",
        "POST,/api/v1/real/consents/challenge"
    })
    void everyOtherRouteAnswers403TermsReacceptanceRequiredNamingV3(String method, String path) throws Exception {
        auth.principal = Optional.of(person);
        lookup.accept(person, "v1");

        Outcome outcome = run(method, path);

        assertThat(outcome.reached()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(403);
        JsonNode problem = json.readTree(outcome.response().getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
        assertThat(problem.get("currentVersion").asText()).isEqualTo("v3");
    }

    @ParameterizedTest(name = "{0} {1} stays reachable without the new acceptance")
    @CsvSource({
        "GET,/api/v1/real/bot-wallet",
        "GET,/api/v1/real/bot-wallet/withdrawals",
        "POST,/api/v1/real/bot-wallet/withdrawals",
        "GET," + WITHDRAWAL
    })
    void theReadOfTheBotWalletAndTheWithdrawalRoutesPassWithoutTheNewAcceptance(String method, String path)
            throws Exception {
        auth.principal = Optional.of(person);
        lookup.accept(person, "v1");

        assertThat(run(method, path).reached()).isTrue();
    }

    @Test
    void afterTheAcceptanceOfV3TheSamePersonPasses() throws Exception {
        auth.principal = Optional.of(person);
        lookup.accept(person, "v1");
        assertThat(run("GET", "/api/v1/bots").reached()).isFalse();

        lookup.accept(person, "v3");

        assertThat(run("GET", "/api/v1/bots").reached()).isTrue();
        assertThat(run("GET", "/api/v1/real/orders").reached()).isTrue();
    }

    @Test
    void theLegalReviewFieldsOfV3AreNeverInAnAnswerOfTheLegalDocuments() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LegalDocumentsView view = new LegalDocumentsView(realCatalog, List::of, mapper);

        assertThat(view.current().body().version()).isEqualTo("v3");
        List<String> answers = List.of(
                mapper.writeValueAsString(view.current().body()),
                mapper.writeValueAsString(view.version("v3").orElseThrow().body()),
                mapper.writeValueAsString(view.history().body()));
        for (String answer : answers) {
            assertThat(answer)
                    .doesNotContainIgnoringCase("reviewed")
                    .doesNotContainIgnoringCase("revisor")
                    .doesNotContain("Assessoria jurídica do projeto");
        }
    }

    private static TermsCatalog realCatalog(Instant now) {
        return new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties("classpath:terms/"),
                LegalSettings.defaults(),
                Clock.fixed(now, ZoneOffset.UTC));
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
}
