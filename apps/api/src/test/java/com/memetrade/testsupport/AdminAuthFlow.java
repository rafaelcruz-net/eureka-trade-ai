package com.memetrade.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Drives the administrator entry of U8 through MockMvc exactly like the administrator app does. */
public final class AdminAuthFlow {

    public static final String COOKIE = "mt_admin_refresh";

    private final MockMvc mvc;
    private final ObjectMapper json;

    public AdminAuthFlow(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public MockHttpServletResponse challengeResponse(String address, String remoteAddress) throws Exception {
        return mvc.perform(post("/api/v1/admin/auth/challenge")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletAddress\":\"" + address + "\"}"))
                .andReturn()
                .getResponse();
    }

    public JsonNode challenge(TestWallet wallet) throws Exception {
        return json.readTree(challengeResponse(wallet.address(), "127.0.0.1").getContentAsString());
    }

    public MockHttpServletResponse verify(TestWallet wallet, String message, String signature) throws Exception {
        return verify(wallet.address(), message, signature, "127.0.0.1");
    }

    public MockHttpServletResponse verify(String address, String message, String signature, String remoteAddress)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("walletAddress", address);
        body.put("message", message);
        body.put("signature", signature);
        body.put("provider", "PHANTOM");
        return mvc.perform(post("/api/v1/admin/auth/verify")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    /** Challenge, sign and verify. */
    public MockHttpServletResponse login(TestWallet wallet) throws Exception {
        String message = challenge(wallet).get("message").asText();
        return verify(wallet, message, wallet.sign(message));
    }

    public MockHttpServletResponse refresh(String refreshToken) throws Exception {
        var request = post("/api/v1/admin/auth/refresh");
        if (refreshToken != null) {
            request = request.cookie(new Cookie(COOKIE, refreshToken));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    public MockHttpServletResponse logout(String accessToken) throws Exception {
        return mvc.perform(post("/api/v1/admin/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn()
                .getResponse();
    }

    public String accessToken(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).get("accessToken").asText();
    }

    public static String refreshToken(MockHttpServletResponse response) {
        String cookie = response.getHeader("Set-Cookie");
        return cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
    }
}
