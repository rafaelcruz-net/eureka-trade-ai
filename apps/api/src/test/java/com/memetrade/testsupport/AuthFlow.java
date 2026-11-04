package com.memetrade.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Drives challenge and verify through MockMvc exactly like the guest app does. */
public final class AuthFlow {

    private final MockMvc mvc;
    private final ObjectMapper json;

    public AuthFlow(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public JsonNode challenge(TestWallet wallet) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/challenge")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletAddress\":\"" + wallet.address() + "\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    public MockHttpServletResponse verify(TestWallet wallet, String message, String signature) throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("walletAddress", wallet.address());
        request.put("message", message);
        request.put("signature", signature);
        request.put("provider", "PHANTOM");
        return mvc.perform(post("/api/v1/auth/verify")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andReturn()
                .getResponse();
    }

    /** U3: verification with an invitation code and the accepted terms version (either may be null). */
    public MockHttpServletResponse verifyWithInvite(
            TestWallet wallet, String message, String signature, String inviteCode, String termsVersion)
            throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("walletAddress", wallet.address());
        request.put("message", message);
        request.put("signature", signature);
        request.put("provider", "SOLFLARE");
        if (inviteCode != null) {
            request.put("inviteCode", inviteCode);
        }
        if (termsVersion != null) {
            request.put("acceptedTermsVersion", termsVersion);
        }
        return mvc.perform(post("/api/v1/auth/verify")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andReturn()
                .getResponse();
    }

    /** U3: challenge, sign and verify with the invitation and the current terms "v1" (account creation). */
    public MockHttpServletResponse createAccount(TestWallet wallet, String inviteCode) throws Exception {
        String message = challenge(wallet).get("message").asText();
        return verifyWithInvite(wallet, message, wallet.sign(message), inviteCode, "v1");
    }

    /** Full login; returns the verify response (access token in the body, refresh token in Set-Cookie). */
    public MockHttpServletResponse login(TestWallet wallet) throws Exception {
        String message = challenge(wallet).get("message").asText();
        return verify(wallet, message, wallet.sign(message));
    }

    public String accessToken(MockHttpServletResponse verifyResponse) throws Exception {
        return json.readTree(verifyResponse.getContentAsString())
                .get("accessToken")
                .asText();
    }

    public static String refreshToken(MockHttpServletResponse verifyResponse) {
        String cookie = verifyResponse.getHeader("Set-Cookie");
        return cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
    }
}
