package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class AuthVerifyIT extends AbstractPostgresIT {

    private static final TestWallet ADMIN = TestWallet.generate();

    @DynamicPropertySource
    static void admins(DynamicPropertyRegistry registry) {
        registry.add("memetrade.auth.admin-wallet-addresses", ADMIN::address);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AuditOutboxRelay relay;

    private AuthFlow auth;

    @BeforeEach
    void setUp() {
        auth = new AuthFlow(mvc, json);
    }

    @Test
    void anAcceptedVerificationReturnsTheGuestSessionAndAStrictRefreshCookie() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());

        MockHttpServletResponse response = auth.login(wallet);

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode session = json.readTree(response.getContentAsString());
        assertThat(session.get("role").asText()).isEqualTo("BETA_USER");
        assertThat(session.get("accessToken").asText()).isNotBlank();
        assertThat(session.has("refreshToken")).isFalse();
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("mt_refresh=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth/refresh");
    }

    @Test
    void aReusedNonceIsRejectedWith401AndTheReason() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String message = auth.challenge(wallet).get("message").asText();
        String signature = wallet.sign(message);
        assertThat(auth.verify(wallet, message, signature).getStatus()).isEqualTo(200);

        MockHttpServletResponse replay = auth.verify(wallet, message, signature);

        assertThat(replay.getStatus()).isEqualTo(401);
        JsonNode problem = json.readTree(replay.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("LOGIN_CHALLENGE_REJECTED");
        assertThat(problem.get("reason").asText()).isEqualTo("NONCE_ALREADY_USED");
    }

    @Test
    void theAdministratorWalletGets403ForbiddenRole() throws Exception {
        TestAccounts.betaUser(jdbc, ADMIN.address());

        MockHttpServletResponse response = auth.login(ADMIN);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json.readTree(response.getContentAsString()).get("code").asText())
                .isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void inviteFieldsAreNotAcceptedYetAndNothingChanges() throws Exception {
        TestWallet wallet = TestWallet.generate();
        JsonNode challenge = auth.challenge(wallet);
        String message = challenge.get("message").asText();
        String body = json.writeValueAsString(json.createObjectNode()
                .put("walletAddress", wallet.address())
                .put("message", message)
                .put("signature", wallet.sign(message))
                .put("provider", "PHANTOM")
                .put("inviteCode", "ABC"));

        int status = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/verify")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(400);
        assertThat(challengeState(challenge.get("nonce").asText())).isEqualTo("ISSUED");
    }

    @Test
    void twoSimultaneousVerificationsOfTheSameNonceOpenOneSession() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        String message = auth.challenge(wallet).get("message").asText();
        String body = json.writeValueAsString(json.createObjectNode()
                .put("walletAddress", wallet.address())
                .put("message", message)
                .put("signature", wallet.sign(message))
                .put("provider", "PHANTOM"));
        Callable<Integer> call = () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
            ResponseEntity<String> response =
                    rest.postForEntity("/api/v1/auth/verify", new HttpEntity<>(body, headers), String.class);
            return response.getStatusCode().value();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(call, call));
            List<Integer> statuses =
                    List.of(results.get(0).get(), results.get(1).get());

            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
            assertThat(jdbc.sql("select count(*) from user_session where user_id = :u")
                            .param("u", seeded.userId())
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eachRefusalWritesExactlyOneLoginRejectedEventBeforeTheResponse() throws Exception {
        TestWallet wallet = TestWallet.generate();
        String message = auth.challenge(wallet).get("message").asText();
        MockHttpServletResponse response = auth.verify(wallet, message, wallet.sign(message));
        String correlationId = response.getHeader("X-Correlation-Id");

        assertThat(response.getStatus()).isEqualTo(401);
        relay.relayBatch();
        List<String> actions = jdbc.sql("select action from audit_event where correlation_id = :c")
                .param("c", correlationId)
                .query(String.class)
                .list();
        assertThat(actions).containsExactly("LOGIN_REJECTED");
    }

    @Test
    void verificationStoresNoIdempotentResponse() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        auth.login(wallet);

        assertThat(jdbc.sql("select count(*) from idempotency_record where scope like :s")
                        .param("s", "wallet:" + wallet.address() + ":auth.verify%")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void aSuspendedTenantCannotSignIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.seed(jdbc, wallet.address(), "SUSPENDED", "ACTIVE");

        MockHttpServletResponse response = auth.login(wallet);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(json.readTree(response.getContentAsString()).get("reason").asText())
                .isEqualTo("UNKNOWN_ACCOUNT");
    }

    private String challengeState(String nonce) {
        return jdbc.sql("select state from login_challenge where nonce = :n")
                .param("n", nonce)
                .query(String.class)
                .single();
    }
}
