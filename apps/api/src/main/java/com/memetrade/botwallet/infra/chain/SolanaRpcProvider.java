package com.memetrade.botwallet.infra.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Solana JSON-RPC provider with its own circuit (reliability-design.md): 3 s per call; the circuit counts only
 * technical failures (timeout, connection, 5xx, 429) in a 60 s window with at least 5 calls and opens at 50% for 30 s.
 * A JSON-RPC error answer is a refusal, not a technical failure. The URL may carry an API key, so it is never logged.
 */
public class SolanaRpcProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final URI endpoint;
    private final HttpClient http;
    private final Duration timeout;
    private final CircuitBreaker circuit;
    private final AtomicLong ids = new AtomicLong();

    public SolanaRpcProvider(String name, URI endpoint, HttpClient http, Duration timeout) {
        this.name = name;
        this.endpoint = endpoint;
        this.http = http;
        this.timeout = timeout;
        this.circuit = CircuitBreaker.of(
                "rpc-" + name,
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                        .slidingWindowSize(60)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordExceptions(RpcUnavailableException.class)
                        .build());
    }

    public String name() {
        return name;
    }

    public CircuitBreaker.State circuitState() {
        return circuit.getState();
    }

    /** Calls {@code method}; returns the {@code result} node. */
    public JsonNode call(String method, Object... params) {
        try {
            return circuit.executeSupplier(() -> send(method, params));
        } catch (CallNotPermittedException open) {
            throw new RpcUnavailableException(name + " circuit is open");
        }
    }

    private JsonNode send(String method, Object... params) {
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", ids.incrementAndGet());
        body.put("method", method);
        ArrayNode array = body.putArray("params");
        for (Object param : params) {
            array.add(JSON.valueToTree(param));
        }
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new RpcUnavailableException(name + " did not answer " + method);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RpcUnavailableException(name + " call interrupted");
        }
        if (response.statusCode() >= 500 || response.statusCode() == 429) {
            throw new RpcUnavailableException(name + " answered " + response.statusCode() + " to " + method);
        }
        try {
            JsonNode answer = JSON.readTree(response.body());
            if (answer.hasNonNull("error")) {
                throw new RpcRefusedException(
                        name + " refused " + method + ": " + answer.get("error").path("code"));
            }
            return answer.path("result");
        } catch (IOException notJson) {
            throw new RpcUnavailableException(name + " answered a body that is not JSON to " + method);
        }
    }

    /** Technical failure of a provider: counted by the circuit; the other provider is tried. */
    public static class RpcUnavailableException extends RuntimeException {
        public RpcUnavailableException(String message) {
            super(message);
        }
    }

    /** The provider answered with a JSON-RPC error: not counted by the circuit. */
    public static class RpcRefusedException extends RuntimeException {
        public RpcRefusedException(String message) {
            super(message);
        }
    }
}
