package com.memetrade.txsigner.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.testsupport.AbstractTxSignerIT;
import com.memetrade.txsigner.testsupport.ConsentText;
import com.memetrade.txsigner.testsupport.TestWallet;
import com.memetrade.txsigner.testsupport.TxBytes;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 9, written after the routes (contract R1, NFR1.5, NFR1.7): service credential (401, previous token accepted
 * during a rotation, 403 for another service's certificate), creation and replay, signature, replay and mismatch,
 * refusal without the transaction, the internal listings without key references, input validation, and the
 * append-only and immutable-anchor rules of the TxSigner database.
 */
@TestPropertySource(properties = "txsigner.real.enabled=true")
class InternalApiIT extends AbstractTxSignerIT {

    private static final Random SEEDED = new Random(11);

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Clock clock;

    private final TestWallet main = TestWallet.generate();

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON);
    }

    private static String nonce() {
        byte[] bytes = new byte[32];
        SEEDED.nextBytes(bytes);
        return Base58.encode(bytes);
    }

    private String consent() {
        Instant issued = clock.instant().minusSeconds(10);
        return ConsentText.activation(main.address())
                .nonce(nonce())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(300))
                .text();
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private MvcResult createWallet(UUID requestId, String message, String signature) throws Exception {
        return mvc.perform(authorized(post("/internal/v1/wallets"))
                        .content(body(Map.of(
                                "requestId", requestId,
                                "mainWalletAddress", main.address(),
                                "consentMessage", message,
                                "consentSignature", signature))))
                .andReturn();
    }

    private String newWallet() throws Exception {
        String message = consent();
        MvcResult created = createWallet(UUID.randomUUID(), message, main.sign(message));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(created.getResponse().getContentAsString())
                .get("botWalletAddress")
                .asText();
    }

    private Map<String, Object> withdrawal(UUID id, String bot, byte[] destination, byte[] blockhash) {
        byte[] message = TxBytes.withdrawal(Base58.decode(bot), destination, 250_000_000L, 450, 2L)
                .blockhash(blockhash)
                .build();
        return Map.of(
                "signingRequestId",
                id,
                "botWalletAddress",
                bot,
                "operationKind",
                "WITHDRAWAL",
                "transactionBase64",
                Base64.getEncoder().encodeToString(TxBytes.unsigned(message, 1)),
                "context",
                Map.of("inputLamports", "250000000", "feeLamports", "5001"));
    }

    @Test
    void withoutAValidServiceTokenTheAnswerIs401() throws Exception {
        mvc.perform(post("/internal/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        mvc.perform(post("/internal/v1/wallets")
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePreviousTokenStillWorksDuringARotation() throws Exception {
        mvc.perform(get("/internal/v1/wallets")
                        .param("createdBefore", "2000-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + PREVIOUS_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void aCertificateOfAnotherServiceIsRefusedWith403() throws Exception {
        X509Certificate web = mock(X509Certificate.class);
        when(web.getSubjectX500Principal()).thenReturn(new X500Principal("CN=web, O=eureka"));

        mvc.perform(get("/internal/v1/wallets")
                        .param("createdBefore", "2000-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + TOKEN)
                        .requestAttr("jakarta.servlet.request.X509Certificate", new X509Certificate[] {web}))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void aWalletIsCreatedOnceAndTheAnswerNeverCarriesTheKeyReference() throws Exception {
        String message = consent();
        UUID requestId = UUID.randomUUID();

        MvcResult first = createWallet(requestId, message, main.sign(message));
        MvcResult again = createWallet(requestId, message, main.sign(message));

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String answer = first.getResponse().getContentAsString();
        assertThat(answer).doesNotContain("sim-key").doesNotContain("kmsKeyRef");
        assertThat(again.getResponse().getContentAsString()).isEqualTo(answer);
        assertThat(kms.createCalls()).isPositive();
    }

    @Test
    void aForgedConsentIsRefusedWith422() throws Exception {
        String message = consent();

        MvcResult refused =
                createWallet(UUID.randomUUID(), message, TestWallet.generate().sign(message));

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(refused.getResponse().getContentAsString())
                        .get("code")
                        .asText())
                .isEqualTo("CONSENT_INVALID");
    }

    @Test
    void aWithdrawalIsSignedRepeatedIdenticallyAndAnotherMessageWithTheSameIdIsRefused() throws Exception {
        String bot = newWallet();
        UUID id = UUID.randomUUID();

        MvcResult first = mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content(body(withdrawal(id, bot, main.publicKey(), TxBytes.filled(7)))))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult again = mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content(body(withdrawal(id, bot, main.publicKey(), TxBytes.filled(7)))))
                .andExpect(status().isOk())
                .andReturn();
        mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content(body(withdrawal(id, bot, main.publicKey(), TxBytes.filled(8)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIGNING_POLICY_REFUSED"))
                .andExpect(jsonPath("$.reason").value("REQUEST_MISMATCH"));

        JsonNode signed = json.readTree(first.getResponse().getContentAsString());
        assertThat(signed.get("signature").asText()).hasSizeBetween(86, 88);
        assertThat(again.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void aWithdrawalToAnotherAddressIsRefusedWithOnlyTheReason() throws Exception {
        String bot = newWallet();

        MvcResult refused = mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content(body(withdrawal(
                                UUID.randomUUID(), bot, TestWallet.generate().publicKey(), TxBytes.filled(7)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("DESTINATION_NOT_ALLOWED"))
                .andReturn();

        assertThat(refused.getResponse().getContentAsString())
                .doesNotContain("transaction")
                .doesNotContain("signedTransactionBase64");
    }

    @Test
    void theInternalListingsNeverCarryTheKeyReference() throws Exception {
        String bot = newWallet();
        mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content(body(withdrawal(UUID.randomUUID(), bot, main.publicKey(), TxBytes.filled(7)))))
                .andExpect(status().isOk());

        String wallets = mvc.perform(get("/internal/v1/wallets")
                        .param("createdBefore", "2100-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String signatures = mvc.perform(get("/internal/v1/signatures")
                        .param("since", "2000-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(wallets)
                .contains(bot)
                .contains("ACTIVE")
                .doesNotContain("sim-key")
                .doesNotContain("kmsKeyRef");
        assertThat(signatures).contains(bot).doesNotContain("signedTransaction");
    }

    @Test
    void lowerAndCloseChangeThePolicyThroughTheConsentsRoute() throws Exception {
        String bot = newWallet();

        mvc.perform(authorized(post("/internal/v1/wallets/" + bot + "/consents"))
                        .content(body(Map.of(
                                "requestId", UUID.randomUUID(),
                                "kind", "LOWER_CAP",
                                "lowerTo", Map.of("capLamports", "100")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capLamports").value("100"));
        mvc.perform(authorized(post("/internal/v1/wallets/" + bot + "/consents"))
                        .content(body(Map.of("requestId", UUID.randomUUID(), "kind", "CLOSE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
    }

    @Test
    void malformedInputIsRefusedWith400BeforeAnyDecoding() throws Exception {
        mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content(body(Map.of(
                                "signingRequestId", UUID.randomUUID(),
                                "botWalletAddress", "0OIl-not-base58",
                                "operationKind", "WITHDRAWAL",
                                "transactionBase64", "AAAA"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content("{\"signingRequestId\":\"" + UUID.randomUUID() + "\",\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authorized(post("/internal/v1/wallets/" + Base58.encode(new byte[32]) + "/consents"))
                        .content(body(Map.of("requestId", UUID.randomUUID(), "kind", "LOWER_CAP"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void theSigningRecordIsAppendOnlyAndThePolicyAnchorIsImmutable() throws Exception {
        String bot = newWallet();
        mvc.perform(authorized(post("/internal/v1/signatures"))
                        .content(body(withdrawal(UUID.randomUUID(), bot, main.publicKey(), TxBytes.filled(7)))))
                .andExpect(status().isOk());

        try (Connection owner = ownerConnection();
                Statement statement = owner.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE signing_record SET decision = 'REFUSED'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM signing_record"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE wallet_policy SET main_wallet_address = '"
                            + TestWallet.generate().address() + "' WHERE bot_wallet_address = '" + bot + "'"))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection app = appConnection();
                Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM wallet_policy"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void theRecoveryRouteRefusesAMainWalletThatDoesNotMatchTheAnchor() throws Exception {
        String bot = newWallet();

        mvc.perform(authorized(post("/internal/v1/recovery/wallets"))
                        .content(body(Map.of(
                                "botWalletAddress",
                                bot,
                                "mainWalletAddress",
                                TestWallet.generate().address()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECOVERY_REFUSED"));
        mvc.perform(authorized(post("/internal/v1/recovery/complete"))).andExpect(status().isNoContent());
    }
}
