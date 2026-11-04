package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import com.memetrade.shared.error.ApiException;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 11 (BR1.4, BR3.4, BR3.7; NFR4.2 to NFR4.4): two requests released together by a start barrier, over real HTTP
 * with their own pooled connections, repeated 50 times per case. The row lock of the invitation and the unique
 * constraint of the wallet are the final guarantees.
 */
class InviteConcurrencyIT extends AbstractU3IT {

    private static final int REPETITIONS = 50;
    private static final Principal ADMIN = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private LoginRateLimiter loginLimiter;

    @Autowired
    private IdentityAdministration administration;

    @Autowired
    private TransactionTemplate transactions;

    private ExecutorService pool;
    private AuthFlow auth;

    @BeforeEach
    void start() {
        pool = Executors.newFixedThreadPool(2);
        auth = new AuthFlow(mvc, json);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void oneInvitationUsedTwiceAtOnceCreatesExactlyOneAccountAndTheOtherReadsUsed() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            loginLimiter.clear();
            TestInvites.Created invite = TestInvites.pending(jdbc).insert();
            Signed first = sign(TestWallet.generate(), invite.code());
            Signed second = sign(TestWallet.generate(), invite.code());

            List<Answer> answers = together(() -> verify(first), () -> verify(second));

            assertThat(answers)
                    .as("repetition %d", i)
                    .extracting(Answer::status)
                    .containsExactlyInAnyOrder(200, 422);
            assertThat(answers)
                    .filteredOn(answer -> answer.status() == 422)
                    .singleElement()
                    .satisfies(answer -> {
                        assertThat(answer.code()).isEqualTo("INVITE_INVALID");
                        assertThat(answer.reason()).isEqualTo("USED");
                    });
            assertThat(accountsCreatedWith(invite.inviteId())).isEqualTo(1);
        }
    }

    @Test
    void oneWalletWithTwoInvitationsAtOnceGetsOneAccountAndTheOtherInvitationStaysPending() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            loginLimiter.clear();
            TestWallet wallet = TestWallet.generate();
            TestInvites.Created a = TestInvites.pending(jdbc).insert();
            TestInvites.Created b = TestInvites.pending(jdbc).insert();
            Signed first = sign(wallet, a.code());
            Signed second = sign(wallet, b.code());

            List<Answer> answers = together(() -> verify(first), () -> verify(second));

            assertThat(answers)
                    .as("repetition %d", i)
                    .extracting(Answer::status)
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(answers)
                    .filteredOn(answer -> answer.status() == 409)
                    .singleElement()
                    .extracting(Answer::code)
                    .isEqualTo("WALLET_ALREADY_LINKED");
            assertThat(jdbc.sql("select count(*) from wallet where address = :a")
                            .param("a", wallet.address())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            assertThat(List.of(status(a.inviteId()), status(b.inviteId())))
                    .containsExactlyInAnyOrder("USED", "PENDING");
            assertThat(List.of(challengeState(first), challengeState(second)))
                    .as("the loser's challenge is consumed by the second transaction")
                    .containsOnly("CONSUMED");
        }
    }

    @Test
    void aRevocationRacingAVerificationEndsInExactlyOneOfTheTwoValidOutcomes() throws Exception {
        int created = 0;
        int revoked = 0;
        for (int i = 0; i < REPETITIONS; i++) {
            loginLimiter.clear();
            TestInvites.Created invite = TestInvites.pending(jdbc).insert();
            Signed signed = sign(TestWallet.generate(), invite.code());

            List<Answer> answers = together(() -> verify(signed), () -> revoke(invite.inviteId()));

            Answer verification = answers.get(0);
            Answer revocation = answers.get(1);
            if (verification.status() == 200) {
                created++;
                assertThat(revocation.status()).as("repetition %d", i).isEqualTo(409);
                assertThat(revocation.reason()).isEqualTo("USED");
                assertThat(status(invite.inviteId())).isEqualTo("USED");
            } else {
                revoked++;
                assertThat(verification.status()).as("repetition %d", i).isEqualTo(422);
                assertThat(verification.reason()).isEqualTo("REVOKED");
                assertThat(revocation.status()).isEqualTo(200);
                assertThat(status(invite.inviteId())).isEqualTo("REVOKED");
                assertThat(accountsCreatedWith(invite.inviteId())).isZero();
            }
        }
        assertThat(created + revoked).isEqualTo(REPETITIONS);
    }

    // ----- helpers -----

    private record Signed(TestWallet wallet, String message, String nonce, String signature, String code) {}

    private record Answer(int status, String code, String reason) {}

    private Signed sign(TestWallet wallet, String code) throws Exception {
        JsonNode challenge = auth.challenge(wallet);
        String message = challenge.get("message").asText();
        return new Signed(wallet, message, challenge.get("nonce").asText(), wallet.sign(message), code);
    }

    private Answer verify(Signed signed) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("walletAddress", signed.wallet().address());
        body.put("message", signed.message());
        body.put("signature", signed.signature());
        body.put("provider", "PHANTOM");
        body.put("inviteCode", signed.code());
        body.put("acceptedTermsVersion", "v1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/auth/verify", new HttpEntity<>(json.writeValueAsString(body), headers), String.class);
        JsonNode problem = json.readTree(response.getBody() == null ? "{}" : response.getBody());
        return new Answer(
                response.getStatusCode().value(),
                problem.path("code").asText(null),
                problem.path("reason").asText(null));
    }

    private Answer revoke(UUID inviteId) {
        try {
            transactions.executeWithoutResult(status -> administration.revoke(inviteId, ADMIN));
            return new Answer(200, null, null);
        } catch (ApiException refused) {
            return new Answer(refused.code().status(), refused.code().code(), refused.reason());
        }
    }

    private List<Answer> together(Callable<Answer> first, Callable<Answer> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Future<Answer>> futures = new ArrayList<>();
        for (Callable<Answer> call : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return call.call();
            }));
        }
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> future : futures) {
            answers.add(future.get(30, TimeUnit.SECONDS));
        }
        return answers;
    }

    private long accountsCreatedWith(UUID inviteId) {
        return jdbc.sql("""
                        select count(*) from invite i join app_user u on u.user_id = i.used_by_user_id
                        where i.invite_id = :id
                        """).param("id", inviteId).query(Long.class).single();
    }

    private String status(UUID inviteId) {
        return jdbc.sql("select status from invite where invite_id = :id")
                .param("id", inviteId)
                .query(String.class)
                .single();
    }

    private String challengeState(Signed signed) {
        return jdbc.sql("select state from login_challenge where nonce = :n")
                .param("n", signed.nonce())
                .query(String.class)
                .single();
    }
}
