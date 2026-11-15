package com.memetrade.botwallet.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.botwallet.domain.ConsentKind;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.shared.web.CorrelationId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client of the TxSigner (contract R1 and its addendum, reliability-design.md): 2 s per call and ONE repetition
 * with the same request id; a circuit that counts only technical failures (timeout, connection, 5xx, 429), never a
 * refusal (4xx), so refusals provoked on purpose never block the withdrawals of everybody. The service token goes in
 * {@code Authorization}; the correlation id is repassed (NFR5.13). Bodies and tokens are never logged.
 */
public class HttpTxSignerClient implements TxSignerPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI base;
    private final String token;
    private final Duration timeout;
    private final CircuitBreaker circuit;

    public HttpTxSignerClient(HttpClient http, URI base, String token, Duration timeout) {
        this.http = http;
        this.base = base;
        this.token = token;
        this.timeout = timeout;
        this.circuit = CircuitBreaker.of(
                "txsigner",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                        .slidingWindowSize(60)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordExceptions(SignerUnavailableException.class)
                        .build());
    }

    public CircuitBreaker.State circuitState() {
        return circuit.getState();
    }

    @Override
    public String createWallet(UUID requestId, String main, String message, String signature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("mainWalletAddress", main);
        body.put("consentMessage", message);
        body.put("consentSignature", signature);
        return post("/internal/v1/wallets", body).path("botWalletAddress").asText();
    }

    @Override
    public void lowerCap(UUID requestId, String botWalletAddress, long capLamports) {
        post(
                "/internal/v1/wallets/" + botWalletAddress + "/consents",
                Map.of(
                        "requestId",
                        requestId,
                        "kind",
                        "LOWER_CAP",
                        "lowerTo",
                        Map.of("capLamports", Long.toString(capLamports))));
    }

    @Override
    public void raise(UUID requestId, String botWalletAddress, ConsentKind kind, String message, String signature) {
        post(
                "/internal/v1/wallets/" + botWalletAddress + "/consents",
                Map.of(
                        "requestId",
                        requestId,
                        "kind",
                        kind.name(),
                        "consentMessage",
                        message,
                        "consentSignature",
                        signature));
    }

    @Override
    public SignOutcome signWithdrawal(
            UUID signingRequestId, String botWalletAddress, String transactionBase64, long lamports, long fee) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signingRequestId", signingRequestId);
        body.put("botWalletAddress", botWalletAddress);
        body.put("operationKind", "WITHDRAWAL");
        body.put("transactionBase64", transactionBase64);
        body.put("context", Map.of("inputLamports", Long.toString(lamports), "feeLamports", Long.toString(fee)));
        try {
            JsonNode signed = post("/internal/v1/signatures", body);
            return new SignOutcome(
                    signed.path("signature").asText(),
                    signed.path("signedTransactionBase64").asText(),
                    null);
        } catch (SignerRefusedException refused) {
            return new SignOutcome(null, null, refused.code());
        }
    }

    @Override
    public void lowerMode(UUID requestId, String botWalletAddress, String mode) {
        post(
                "/internal/v1/wallets/" + botWalletAddress + "/consents",
                Map.of("requestId", requestId, "kind", "LOWER_MODE", "lowerTo", Map.of("mode", mode)));
    }

    @Override
    public SignOutcome signSwap(String botWalletAddress, com.memetrade.botwallet.api.SwapSigningRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mode", request.mode().name());
        context.put("side", request.side());
        if (request.inputLamports() != null) {
            context.put("inputLamports", Long.toString(request.inputLamports()));
        }
        if (request.inputBaseUnits() != null) {
            context.put("inputBaseUnits", Long.toString(request.inputBaseUnits()));
        }
        context.put("quotedOut", Long.toString(request.quotedOut()));
        context.put("minOut", Long.toString(request.minOut()));
        context.put("maxSlippageBps", request.maxSlippageBps());
        context.put("feeLamports", Long.toString(request.feeLamports()));
        context.put("mint", request.mint());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signingRequestId", request.signingRequestId());
        body.put("botWalletAddress", botWalletAddress);
        body.put("operationKind", "SWAP");
        body.put("transactionBase64", request.transactionBase64());
        body.put("context", context);
        try {
            JsonNode signed = post("/internal/v1/signatures", body);
            return new SignOutcome(
                    signed.path("signature").asText(),
                    signed.path("signedTransactionBase64").asText(),
                    null);
        } catch (SignerRefusedException refused) {
            return new SignOutcome(null, null, refused.code());
        }
    }

    /** An authenticated read that touches nothing (the list of wallets created before the epoch is always empty). */
    @Override
    public boolean reachable() {
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(base.resolve("/internal/v1/wallets?createdBefore=1970-01-01T00:00:00Z"))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            return circuit.executeSupplier(() -> {
                try {
                    return send(path, body);
                } catch (SignerUnavailableException first) {
                    // One repetition with the same request id (contract R1): the TxSigner is idempotent by it.
                    return send(path, body);
                }
            });
        } catch (CallNotPermittedException open) {
            throw new SignerUnavailableException("TxSigner circuit is open");
        }
    }

    private JsonNode send(String path, Object body) {
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(base.resolve(path))
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Correlation-Id", CorrelationId.current())
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new SignerUnavailableException("TxSigner did not answer");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SignerUnavailableException("TxSigner call interrupted");
        }
        int status = response.statusCode();
        if (status >= 500 || status == 429) {
            throw new SignerUnavailableException("TxSigner answered " + status);
        }
        JsonNode answer = readOrEmpty(response.body());
        if (status >= 400) {
            String reason = answer.path("reason").asText(null);
            throw new SignerRefusedException(
                    reason != null ? reason : answer.path("code").asText("HTTP_" + status));
        }
        return answer;
    }

    private static JsonNode readOrEmpty(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (IOException notJson) {
            return JSON.createObjectNode();
        }
    }
}
