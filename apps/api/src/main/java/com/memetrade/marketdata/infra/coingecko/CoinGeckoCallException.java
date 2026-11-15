package com.memetrade.marketdata.infra.coingecko;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketSourceException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Failure of ONE call to the CoinGecko API (security-design.md "Mensagens de erro"). The message carries only the
 * route name and the HTTP code, never the URL, a header, the API key or the body. Only time-outs, network errors and
 * 5xx answers are retryable (reliability-design.md Q4): a 3xx, a 429, another 4xx and an unusable payload never are.
 */
public class CoinGeckoCallException extends MarketSourceException {

    /** What went wrong, for the decision to retry, the circuit and the metrics. */
    public enum Kind {
        TIMEOUT,
        NETWORK,
        SERVER_ERROR,
        RATE_LIMITED,
        REDIRECT,
        CLIENT_ERROR,
        INVALID_PAYLOAD,
        CIRCUIT_OPEN
    }

    private final Kind kind;
    private final transient Optional<Duration> retryAfter;

    CoinGeckoCallException(Kind kind, String route, String detail, Optional<Duration> retryAfter) {
        super(category(kind), retryable(kind), route + " " + detail);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    static CoinGeckoCallException of(Kind kind, String route, String detail) {
        return new CoinGeckoCallException(kind, route, detail, Optional.empty());
    }

    public Kind kind() {
        return kind;
    }

    /** The {@code Retry-After} of a 429, when it was a number of seconds. */
    public Optional<Duration> retryAfter() {
        return retryAfter;
    }

    /** A technical failure counted by the circuit breaker (NFR4.2): time-out, network error or 5xx. */
    public boolean technical() {
        return kind == Kind.TIMEOUT || kind == Kind.NETWORK || kind == Kind.SERVER_ERROR;
    }

    private static boolean retryable(Kind kind) {
        return kind == Kind.TIMEOUT || kind == Kind.NETWORK || kind == Kind.SERVER_ERROR;
    }

    private static FailureCategory category(Kind kind) {
        return switch (kind) {
            case TIMEOUT -> FailureCategory.TIMEOUT;
            case INVALID_PAYLOAD -> FailureCategory.INVALID_PAYLOAD;
            default -> FailureCategory.ERROR_RESPONSE;
        };
    }
}
