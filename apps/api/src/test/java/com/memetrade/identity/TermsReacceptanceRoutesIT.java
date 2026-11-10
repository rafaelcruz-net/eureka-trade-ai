package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.tenancy.RouteRegistry;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 4 of u3-reacceptance, the mandatory test of the team: "a versão nova dos termos exige novo aceite antes de
 * qualquer outra rota" (BR3.1 to BR3.3; L3; NFR2.6, NFR9.1; US4.1). It walks every guest route of
 * {@code tenancy/route-registry.yml} with a person who accepted only v1 while v2 is current: every route that needs a
 * session and lies outside the allowed prefixes answers {@code 403 TERMS_REACCEPTANCE_REQUIRED} with
 * {@code currentVersion}; no allowed route does; after the acceptance the same token passes.
 */
@Tag("u3-reacceptance")
@TestPropertySource(properties = "memetrade.terms.location=classpath:terms-test/reviewed/")
class TermsReacceptanceRoutesIT extends AbstractPostgresIT {

    /** L3 and application.yml: the prefixes a locked guest still reaches. */
    static final List<String> ALLOWED = List.of(
            "/api/v1/legal/",
            "/api/v1/terms/acceptances",
            "/api/v1/account",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh",
            // u4-data-requests (BR4.20): the data subject requests stay reachable during the lock.
            "/api/v1/privacy/");

    /** Routes read without a session (AuthenticationFilter): no principal, so the lock never applies to them. */
    static final Set<String> WITHOUT_SESSION =
            Set.of("POST /api/v1/auth/challenge", "POST /api/v1/auth/verify", "GET /api/v1/invites/{code}");

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void theNewVersionOfTheTermsRequiresTheNewAcceptanceBeforeAnyOtherRoute() throws Exception {
        String token = outdatedGuestToken();
        List<RouteRegistry.Entry> guestRoutes = guestRoutes();
        assertThat(guestRoutes).extracting(RouteRegistry.Entry::key).contains("POST /api/v1/terms/acceptances");

        List<String> wronglyLocked = new ArrayList<>();
        List<String> wronglyOpen = new ArrayList<>();
        for (RouteRegistry.Entry route : guestRoutes) {
            MockHttpServletResponse response = call(route.key(), token);
            boolean locked = isLock(response);
            if (expectedLocked(route.key()) && !locked) {
                wronglyOpen.add(route.key() + " -> " + response.getStatus());
            }
            if (!expectedLocked(route.key()) && locked) {
                wronglyLocked.add(route.key());
            }
        }

        assertThat(wronglyOpen)
                .as("guest routes that let an outdated person through")
                .isEmpty();
        assertThat(wronglyLocked)
                .as("allowed or public routes that were locked")
                .isEmpty();
        assertThat(guestRoutes.stream().filter(route -> expectedLocked(route.key())))
                .as("the walk covers the bot, ledger, P&L, scanner and feedback routes")
                .extracting(RouteRegistry.Entry::key)
                .contains(
                        "GET /api/v1/bots",
                        "POST /api/v1/bots",
                        "GET /api/v1/pnl",
                        "GET /api/v1/ledger",
                        "GET /api/v1/scanner/tokens",
                        "POST /api/v1/feedback");
    }

    /** BR3.3: every bot route is locked; after the acceptance the same token reads the bots again. */
    @Test
    void afterTheAcceptanceTheSameTokenPassesTheBotRoutes() throws Exception {
        String token = outdatedGuestToken();
        assertThat(isLock(call("GET /api/v1/bots", token))).isTrue();

        MockHttpServletResponse accepted = mvc.perform(request(HttpMethod.POST, "/api/v1/terms/acceptances")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"v2\"}"))
                .andReturn()
                .getResponse();

        assertThat(accepted.getStatus()).isEqualTo(200);
        MockHttpServletResponse bots = call("GET /api/v1/bots", token);
        assertThat(isLock(bots)).isFalse();
        assertThat(bots.getStatus()).isEqualTo(200);
    }

    /** The account of a person without any acceptance is locked too, and still reads its Account. */
    @Test
    void anAccountWithoutAnyAcceptanceIsLockedButReadsItsAccount() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUserWithoutAcceptance(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(wallet));

        assertThat(isLock(call("GET /api/v1/pnl", token))).isTrue();
        MockHttpServletResponse account = call("GET /api/v1/account", token);
        assertThat(account.getStatus()).isEqualTo(200);
        assertThat(json.readTree(account.getContentAsString()).has("terms")).isFalse();
    }

    // ----- helpers -----

    private String outdatedGuestToken() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        return auth.accessToken(auth.login(wallet));
    }

    /**
     * Guest routes of the registry, with logout LAST so the walk keeps its session until the end. Administrative routes
     * are out: an administrator token never reaches the lock and a guest token gets FORBIDDEN_ROLE there first.
     */
    private static List<RouteRegistry.Entry> guestRoutes() throws Exception {
        return RouteRegistry.load().routes().stream()
                .filter(route -> !"ADMIN".equals(route.scope()))
                .sorted(Comparator.comparing(route -> route.key().equals("POST /api/v1/auth/logout")))
                .toList();
    }

    static boolean expectedLocked(String key) {
        String path = key.substring(key.indexOf(' ') + 1);
        if (WITHOUT_SESSION.contains(key)) {
            return false;
        }
        return ALLOWED.stream()
                .noneMatch(
                        prefix -> path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/"));
    }

    private boolean isLock(MockHttpServletResponse response) throws Exception {
        if (response.getStatus() != 403) {
            return false;
        }
        JsonNode problem = json.readTree(response.getContentAsString());
        boolean lock = "TERMS_REACCEPTANCE_REQUIRED".equals(problem.path("code").asText());
        if (lock) {
            assertThat(problem.path("currentVersion").asText()).isEqualTo("v2");
        }
        return lock;
    }

    private MockHttpServletResponse call(String key, String token) throws Exception {
        String method = key.substring(0, key.indexOf(' '));
        String path = concrete(key.substring(key.indexOf(' ') + 1));
        return mvc.perform(request(HttpMethod.valueOf(method), path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse();
    }

    /** A concrete path for a template of the registry: synthetic values only, never a real mint or wallet. */
    private static String concrete(String template) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder path = new StringBuilder();
        while (matcher.find()) {
            String value =
                    switch (matcher.group(1)) {
                        case "chain" -> "solana";
                        case "mint" -> SyntheticIds.mint();
                        case "code" -> "A".repeat(43);
                        case "version" -> "v1";
                        default -> UUID.randomUUID().toString();
                    };
            matcher.appendReplacement(path, value);
        }
        matcher.appendTail(path);
        return path.toString();
    }
}
