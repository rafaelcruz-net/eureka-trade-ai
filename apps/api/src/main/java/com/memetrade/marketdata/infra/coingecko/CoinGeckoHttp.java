package com.memetrade.marketdata.infra.coingecko;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoCallException.Kind;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The HTTPS calls to the CoinGecko API (U2 security-design.md, NFR1.1 to NFR1.3). Worker only.
 *
 * <ul>
 *   <li>{@link RestClient} over {@link JdkClientHttpRequestFactory}, with a JDK {@link HttpClient} that NEVER follows
 *       a redirect: any 3xx is a non-retryable failure; connection limit {@code connectTimeout} (2 s) and response
 *       limit {@code callTimeout} (4 s);
 *   <li>{@code Accept-Encoding: identity}; a compressed answer is refused, so the 2 MB cut always applies to the real
 *       bytes and a compression bomb never passes;
 *   <li>the body is read as a stream through a counter that fails past {@value #MAX_BODY_BYTES} bytes, straight into
 *       Jackson with {@link StreamReadConstraints} (depth 32, text 10 000, number 64 characters, document 2 MB) and
 *       numbers as {@link java.math.BigDecimal};
 *   <li>every mint is checked as Base58 of 32 to 44 characters BEFORE it enters a URL;
 *   <li>the API key travels only in the authentication header; no request is logged and every error message carries
 *       only the route name and the HTTP code (never the URL, a header or the body).
 * </ul>
 */
public class CoinGeckoHttp {

    static final long MAX_BODY_BYTES = 2L * 1024 * 1024;
    static final int MAX_NESTING = 32;
    static final int MAX_STRING = 10_000;
    static final int MAX_NUMBER = 64;
    static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    private static final Pattern MINT = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}");
    private static final Pattern NETWORK = Pattern.compile("[a-z0-9-]{1,32}");

    /** The routes used by U2; the name is the only part of a call that reaches a log, an error or a metric. */
    public enum Route {
        TOKEN_PRICES("token_prices"),
        NEW_POOLS("new_pools"),
        TRENDING_POOLS("trending_pools"),
        TOKEN_INFO("token_info"),
        TOKEN_POOLS("token_pools"),
        KEY_USAGE("key_usage");

        private final String label;

        Route(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String baseUrl;
    private final String apiKeyHeader;
    private final String apiKey;
    private final RestClient client;
    private final ObjectMapper json;
    private final Duration callTimeout;
    /** Threads of the calls, so the deadline can cancel a call stuck in the body. Daemon: never hold the JVM. */
    private final ExecutorService calls;

    private static final AtomicInteger THREADS = new AtomicInteger();

    public CoinGeckoHttp(
            String baseUrl, String apiKeyHeader, String apiKey, Duration connectTimeout, Duration callTimeout) {
        this.baseUrl = stripSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKeyHeader = Objects.requireNonNull(apiKeyHeader, "apiKeyHeader");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(callTimeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.json = jsonMapper();
        this.callTimeout = Objects.requireNonNull(callTimeout, "callTimeout");
        this.calls = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "coingecko-http-" + THREADS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Price, total reserve (liquidity) and 24 h volume of up to 30 mints in one call (BR1.3). */
    public JsonNode tokenPrices(String network, List<String> mints) {
        if (mints.isEmpty()) {
            throw new IllegalArgumentException("At least one mint");
        }
        mints.forEach(CoinGeckoHttp::checkMint);
        return get(
                Route.TOKEN_PRICES,
                "/onchain/simple/networks/" + checkNetwork(network) + "/token_price/" + String.join(",", mints)
                        + "?include_24hr_vol=true&include_total_reserve_in_usd=true");
    }

    /** The newest pools of the network, with their base token (BR1.1). */
    public JsonNode newPools(String network) {
        return get(
                Route.NEW_POOLS, "/onchain/networks/" + checkNetwork(network) + "/new_pools?include=base_token&page=1");
    }

    /** The trending pools of the network, with their base token (BR1.1). */
    public JsonNode trendingPools(String network) {
        return get(
                Route.TRENDING_POOLS,
                "/onchain/networks/" + checkNetwork(network) + "/trending_pools?include=base_token&page=1");
    }

    /** Token information: holders, name, symbol and decimals (BR1.3 details). */
    public JsonNode tokenInfo(String network, String mint) {
        checkMint(mint);
        return get(Route.TOKEN_INFO, "/onchain/networks/" + checkNetwork(network) + "/tokens/" + mint + "/info");
    }

    /** The top pools of a token; the first one gives the creation time (BR1.3 details). */
    public JsonNode tokenPools(String network, String mint) {
        checkMint(mint);
        return get(
                Route.TOKEN_POOLS, "/onchain/networks/" + checkNetwork(network) + "/tokens/" + mint + "/pools?page=1");
    }

    /** The usage of the key reported by CoinGecko (NFR3.7). */
    public JsonNode keyUsage() {
        return get(Route.KEY_USAGE, "/key");
    }

    /** NFR1.2: a mint enters a URL only as Base58 of 32 to 44 characters. */
    static String checkMint(String mint) {
        if (mint == null || !MINT.matcher(mint).matches()) {
            throw new IllegalArgumentException("Mint must be Base58 of 32 to 44 characters");
        }
        return mint;
    }

    private static String checkNetwork(String network) {
        if (network == null || !NETWORK.matcher(network).matches()) {
            throw new IllegalArgumentException("Invalid network id");
        }
        return network;
    }

    /**
     * One call with a deadline of {@code callTimeout} over the WHOLE call, headers and body: the JDK client's own
     * timeout only covers the headers, so the call runs on its own thread and is cancelled (interrupted) when the
     * deadline passes, which also stops a body that trickles in slowly.
     */
    JsonNode get(Route route, String pathAndQuery) {
        Future<JsonNode> call = calls.submit(() -> exchange(route, pathAndQuery));
        try {
            return call.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            call.cancel(true);
            throw CoinGeckoCallException.of(Kind.TIMEOUT, route.label(), "timed out");
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw CoinGeckoCallException.of(Kind.TIMEOUT, route.label(), "interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof CoinGeckoCallException failure) {
                throw failure;
            }
            throw CoinGeckoCallException.of(Kind.NETWORK, route.label(), "call failed");
        }
    }

    private JsonNode exchange(Route route, String pathAndQuery) {
        try {
            return client.get()
                    .uri(URI.create(baseUrl + pathAndQuery))
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .header(apiKeyHeader, apiKey)
                    .exchange((request, response) -> read(route, response), true);
        } catch (CoinGeckoCallException failure) {
            throw failure;
        } catch (ResourceAccessException io) {
            throw io(route, io.getCause());
        } catch (RestClientException other) {
            throw CoinGeckoCallException.of(Kind.NETWORK, route.label(), "call failed");
        }
    }

    private JsonNode read(Route route, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        int code = status.value();
        if (status.is3xxRedirection()) {
            throw CoinGeckoCallException.of(Kind.REDIRECT, route.label(), "answered " + code + " (redirect refused)");
        }
        if (code == 429) {
            throw new CoinGeckoCallException(
                    Kind.RATE_LIMITED,
                    route.label(),
                    "answered 429",
                    retryAfter(response.getHeaders().getFirst("Retry-After")));
        }
        if (status.is5xxServerError()) {
            throw CoinGeckoCallException.of(Kind.SERVER_ERROR, route.label(), "answered " + code);
        }
        if (!status.is2xxSuccessful()) {
            throw CoinGeckoCallException.of(Kind.CLIENT_ERROR, route.label(), "answered " + code);
        }
        String encoding = response.getHeaders().getFirst("Content-Encoding");
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
            throw CoinGeckoCallException.of(Kind.INVALID_PAYLOAD, route.label(), "answered compressed (refused)");
        }
        try (InputStream body = new LimitedInputStream(response.getBody(), MAX_BODY_BYTES)) {
            JsonNode node = json.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw CoinGeckoCallException.of(Kind.INVALID_PAYLOAD, route.label(), "answered an empty body");
            }
            return node;
        } catch (BodyTooLargeException tooLarge) {
            throw CoinGeckoCallException.of(Kind.INVALID_PAYLOAD, route.label(), "answered more than 2 MB");
        } catch (JsonProcessingException malformed) {
            throw CoinGeckoCallException.of(Kind.INVALID_PAYLOAD, route.label(), "answered an unusable JSON");
        } catch (IOException io) {
            throw io(route, io);
        }
    }

    private static CoinGeckoCallException io(Route route, Throwable cause) {
        if (cause instanceof BodyTooLargeException) {
            return CoinGeckoCallException.of(Kind.INVALID_PAYLOAD, route.label(), "answered more than 2 MB");
        }
        if (cause instanceof HttpTimeoutException || cause instanceof HttpConnectTimeoutException) {
            return CoinGeckoCallException.of(Kind.TIMEOUT, route.label(), "timed out");
        }
        return CoinGeckoCallException.of(Kind.NETWORK, route.label(), "network error");
    }

    /** A {@code Retry-After} in whole seconds (an HTTP date is ignored), capped at one hour. */
    static Optional<Duration> retryAfter(String header) {
        if (header == null || !header.trim().matches("\\d{1,6}")) {
            return Optional.empty();
        }
        Duration wait = Duration.ofSeconds(Long.parseLong(header.trim()));
        return Optional.of(wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait);
    }

    static ObjectMapper jsonMapper() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING)
                        .maxStringLength(MAX_STRING)
                        .maxNumberLength(MAX_NUMBER)
                        .maxDocumentLength(MAX_BODY_BYTES)
                        .build())
                .build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
                .build();
    }

    private static String stripSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public String toString() {
        // Never the key nor the full URL (NFR1.1).
        return "CoinGeckoHttp["
                + baseUrl.toLowerCase(Locale.ROOT).replaceAll("^https?://", "").split("/")[0] + "]";
    }

    /** Thrown by {@link LimitedInputStream} past the limit. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("Body larger than the limit");
        }
    }

    /** Counts the bytes read and fails past the limit, so a large body never fills the memory. */
    static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int read = super.read();
            if (read >= 0) {
                add(1);
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                add(read);
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            add(skipped);
            return skipped;
        }

        private void add(long bytes) throws BodyTooLargeException {
            count += bytes;
            if (count > limit) {
                throw new BodyTooLargeException();
            }
        }
    }
}
