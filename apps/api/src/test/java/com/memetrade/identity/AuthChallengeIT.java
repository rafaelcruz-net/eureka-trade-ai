package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AuthChallengeIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void issuesAChallengeStoredAsIssuedWithAFiveMinuteExpiry() throws Exception {
        TestWallet wallet = TestWallet.generate();

        JsonNode body = json.readTree(mvc.perform(challenge(wallet.address(), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("localhost:3000"))
                .andExpect(jsonPath("$.chain").value("solana:mainnet"))
                .andReturn()
                .getResponse()
                .getContentAsString());

        String nonce = body.get("nonce").asText();
        assertThat(body.get("message").asText()).contains("Nonce: " + nonce).contains(wallet.address());
        assertThat(Duration.between(
                        Instant.parse(body.get("issuedAt").asText()),
                        Instant.parse(body.get("expiresAt").asText())))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(jdbc.sql("select state from login_challenge where nonce = :n")
                        .param("n", nonce)
                        .query(String.class)
                        .single())
                .isEqualTo("ISSUED");
    }

    @Test
    void anInvalidAddressIsValidationFailedAndNothingIsStored() throws Exception {
        mvc.perform(challenge("not-a-wallet-0OIl", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("walletAddress"));

        assertThat(challengesFor("not-a-wallet-0OIl")).isZero();
    }

    @Test
    void anUndeclaredFieldIsRefusedWithoutStoringAnything() throws Exception {
        TestWallet wallet = TestWallet.generate();

        mvc.perform(post("/api/v1/auth/challenge")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletAddress\":\"" + wallet.address() + "\",\"privateKey\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("privateKey"));

        assertThat(challengesFor(wallet.address())).isZero();
    }

    @Test
    void repeatingWithTheSameKeyReturnsTheSameChallenge() throws Exception {
        TestWallet wallet = TestWallet.generate();
        UUID key = UUID.randomUUID();

        String first = mvc.perform(challenge(wallet.address(), key))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String second = mvc.perform(challenge(wallet.address(), key))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(json.readTree(second).get("nonce"))
                .isEqualTo(json.readTree(first).get("nonce"));
        assertThat(challengesFor(wallet.address())).isEqualTo(1);
    }

    @Test
    void aMissingIdempotencyKeyIsRefused() throws Exception {
        TestWallet wallet = TestWallet.generate();

        mvc.perform(post("/api/v1/auth/challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletAddress\":\"" + wallet.address() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("Idempotency-Key"));

        assertThat(challengesFor(wallet.address())).isZero();
    }

    private static MockHttpServletRequestBuilder challenge(String address, UUID key) {
        return post("/api/v1/auth/challenge")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"walletAddress\":\"" + address + "\"}");
    }

    private int challengesFor(String address) {
        return jdbc.sql("select count(*) from login_challenge where wallet_address = :a")
                .param("a", address)
                .query(Integer.class)
                .single();
    }
}
