package com.memetrade.realexecution.infra.jupiter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.realexecution.domain.NoRouteException;
import com.memetrade.realexecution.domain.RouterQuote;
import com.memetrade.realexecution.domain.RouterUnavailableException;
import com.memetrade.realexecution.domain.SwapRouter;
import com.memetrade.realexecution.domain.solana.AccountRef;
import com.memetrade.realexecution.domain.solana.SwapInstruction;
import com.memetrade.shared.solana.Base58Codec;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * X1, the Jupiter swap API (U4-NFR8.7, U4-NFR3.3, U4-NFR1.5): {@code GET /quote} and {@code POST /swap-instructions}
 * with 2 s per call, no repetition (a quote older than 10 s is never reused anyway) and a circuit that counts only
 * technical failures (timeout, connection, 5xx, 429). The route is asked with {@code maxAccounts} so the message fits
 * one packet with static keys only; the lookup tables of the answer are ignored. The plan used needs no key, so nothing
 * here is a secret; the bodies are never logged. The ONLY class of {@code realexecution} that reaches the network
 * (U4-NFR1.4, {@code RealExecutionArchitectureTest}).
 */
public class JupiterClient implements SwapRouter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BASE58_ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");
    private static final String WSOL = "So11111111111111111111111111111111111111112";
    private static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;
    private final long maxPriorityFeeLamports;
    private final CircuitBreaker circuit;

    public JupiterClient(HttpClient http, String baseUrl, Duration timeout, long maxPriorityFeeLamports) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.maxPriorityFeeLamports = maxPriorityFeeLamports;
        this.circuit = CircuitBreaker.of(
                "jupiter",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                        .slidingWindowSize(60)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordExceptions(RouterUnavailableException.class)
                        .ignoreExceptions(NoRouteException.class)
                        .build());
    }

    public CircuitBreaker.State circuitState() {
        return circuit.getState();
    }

    @Override
    public RouterQuote quote(String inputMint, String outputMint, long amount, int slippageBps, int maxAccounts) {
        requireMint(inputMint);
        requireMint(outputMint);
        String query = "?inputMint=" + inputMint + "&outputMint=" + outputMint + "&amount=" + amount
                + "&slippageBps=" + slippageBps + "&maxAccounts=" + maxAccounts
                + "&restrictIntermediateTokens=true&swapMode=ExactIn";
        JsonNode answer = guarded(() -> get("/quote" + query));
        long in = Long.parseLong(answer.path("inAmount").asText("0"));
        long out = Long.parseLong(answer.path("outAmount").asText("0"));
        if (in != amount || out < 0) {
            throw new RouterUnavailableException("The Jupiter answered a quote for another amount");
        }
        return new RouterQuote(inputMint, outputMint, in, out, slippageBps, maxAccounts, answer.toString());
    }

    @Override
    public List<SwapInstruction> swapInstructions(RouterQuote quote, String userPublicKey) {
        requireMint(userPublicKey);
        ObjectNode body = JSON.createObjectNode();
        try {
            body.set("quoteResponse", JSON.readTree(quote.rawQuote()));
        } catch (IOException notJson) {
            throw new IllegalArgumentException("The recorded quote is not JSON");
        }
        body.put("userPublicKey", userPublicKey);
        body.put("wrapAndUnwrapSol", true);
        body.put("dynamicComputeUnitLimit", true);
        ObjectNode priority = body.putObject("prioritizationFeeLamports").putObject("priorityLevelWithMaxLamports");
        priority.put("maxLamports", maxPriorityFeeLamports);
        priority.put("priorityLevel", "medium");
        JsonNode answer = guarded(() -> post("/swap-instructions", body));
        List<SwapInstruction> instructions = new ArrayList<>();
        answer.path("computeBudgetInstructions").forEach(node -> instructions.add(instruction(node)));
        answer.path("otherInstructions").forEach(node -> instructions.add(instruction(node)));
        answer.path("setupInstructions").forEach(node -> instructions.add(instruction(node)));
        JsonNode swap = answer.path("swapInstruction");
        if (swap.isMissingNode() || swap.isNull()) {
            throw new RouterUnavailableException("The Jupiter answered without a swap instruction");
        }
        instructions.add(instruction(swap));
        JsonNode cleanup = answer.path("cleanupInstruction");
        if (!cleanup.isMissingNode() && !cleanup.isNull()) {
            instructions.add(instruction(cleanup));
        }
        return instructions;
    }

    @Override
    public void probe() {
        quote(WSOL, USDC, 1_000_000L, 50, 20);
    }

    static SwapInstruction instruction(JsonNode node) {
        List<AccountRef> accounts = new ArrayList<>();
        for (JsonNode account : node.path("accounts")) {
            accounts.add(new AccountRef(
                    Base58Codec.decode(account.path("pubkey").asText(), 32),
                    account.path("isSigner").asBoolean(),
                    account.path("isWritable").asBoolean()));
        }
        return new SwapInstruction(
                Base58Codec.decode(node.path("programId").asText(), 32),
                accounts,
                Base64.getDecoder().decode(node.path("data").asText("")));
    }

    private JsonNode guarded(Supplier<JsonNode> call) {
        try {
            return circuit.executeSupplier(call);
        } catch (CallNotPermittedException open) {
            throw new RouterUnavailableException("The Jupiter circuit is open");
        }
    }

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build());
    }

    private JsonNode post(String path, JsonNode body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build());
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new RouterUnavailableException("The Jupiter did not answer");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RouterUnavailableException("The call to the Jupiter was interrupted");
        }
        int status = response.statusCode();
        // 5xx and 429 are technical failures (circuit, UpstreamTracker, metric); any other 4xx is a refusal of the
        // route of this order (review of the U4, Major 1): the order fails NO_ROUTE and nothing counts as an outage.
        if (status >= 500 || status == 429) {
            throw new RouterUnavailableException("The Jupiter answered " + status);
        }
        if (status >= 400) {
            throw new NoRouteException("The Jupiter refused the route: " + status);
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException notJson) {
            throw new RouterUnavailableException("The Jupiter answered a body that is not JSON");
        }
    }

    private static void requireMint(String mint) {
        if (mint == null || !BASE58_ADDRESS.matcher(mint).matches()) {
            throw new IllegalArgumentException("Not a Base58 address");
        }
    }
}
