package com.memetrade.marketdata.infra.coingecko;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.infra.coingecko.CallBudget.Decision;
import com.memetrade.marketdata.infra.coingecko.CallBudget.Kind;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoHttp.Route;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoMetrics.Outcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real market source {@code coingecko} (261113-trading-real-bot U2, BR1.1, BR1.3, BR1.9, NFR4.2, NFR4.6), worker
 * only. Each {@link #fetch} is one ROUND, run in series with the injectable clock:
 *
 * <ol>
 *   <li><b>prices first</b>: price, liquidity (total reserve) and 24 h volume of the active tokens in batches of
 *       {@code batchSize} (30);
 *   <li><b>discovery</b>, every {@code discoveryInterval} (5 min) and only with at least {@value #DISCOVERY_MIN_LEFT_S}
 *       s of the deadline left: new and trending pools of the network, without repeated mints, at most
 *       {@code discoveryLimit} (50), plus the {@code watched} tokens of the request; a token leaves the active set when
 *       the discovery no longer brings it and nobody watches it. New tokens are priced in the same round when time is
 *       left;
 *   <li><b>details</b>, only with at least {@value #DETAIL_MIN_LEFT_S} s left and within 2 calls per minute: holders
 *       ({@code info}) and the creation time of the main pool ({@code pools}), oldest first, each renewed every
 *       {@code detailRefresh} (60 min).
 * </ol>
 *
 * The round has an internal deadline of {@code roundDeadline} (18 s) and opens no new call after
 * {@code lastCallStart} (12 s). Every call goes through {@link CallBudget} (counted before it is made) and through a
 * Resilience4j circuit (60 s window, at least 5 calls, 50% technical failures, 60 s open). One retry only after a
 * time-out, a network error or a 5xx, when it still fits; a 429 is never retried: the round stops and its
 * {@code Retry-After} holds the next rounds. Only the fields renewed in this round are returned
 * ({@link FetchResult#round()} carries the {@link RoundSummary}); the others keep their earlier observation. The active
 * set and the times live in memory and are rebuilt by the first round after a restart.
 */
public class CoinGeckoAdapter implements MarketSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoAdapter.class);

    static final long DISCOVERY_MIN_LEFT_S = 8;
    static final long DETAIL_MIN_LEFT_S = 4;

    private static final Pattern MINT = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}");

    private final CoinGeckoProperties properties;
    private final CoinGeckoHttp http;
    private final CallBudget budget;
    private final Clock clock;
    private final CoinGeckoMetrics metrics;
    private final CircuitBreaker circuit;
    private final ValueParser parser;
    private final String chain;

    /** Discovered tokens of the active set, in discovery order. */
    private final Map<TokenId, TokenInfo> discovered = new LinkedHashMap<>();
    /** Every token whose symbol, name and decimals are known (discovery or details). */
    private final Map<TokenId, TokenInfo> infos = new ConcurrentHashMap<>();
    /** The active set of the last round (discovered and watched), for the gauges. */
    private volatile Set<TokenId> active = Set.of();

    private final Map<TokenId, Instant> enteredAt = new ConcurrentHashMap<>();
    private final Map<TokenId, Instant> pricedAt = new ConcurrentHashMap<>();
    private final Map<TokenId, Instant> infoAt = new ConcurrentHashMap<>();
    private final Map<TokenId, Instant> poolsAt = new ConcurrentHashMap<>();
    /** Last attempt of each detail call (success or not), for the order of the queue. */
    private final Map<TokenId, Instant> infoTriedAt = new ConcurrentHashMap<>();

    private final Map<TokenId, Instant> poolsTriedAt = new ConcurrentHashMap<>();
    private Instant lastDiscovery;
    private Instant notBefore;

    public CoinGeckoAdapter(
            CoinGeckoProperties properties,
            CoinGeckoHttp http,
            CallBudget budget,
            Clock clock,
            CoinGeckoMetrics metrics) {
        this(properties, http, budget, clock, metrics, defaultCircuit());
    }

    CoinGeckoAdapter(
            CoinGeckoProperties properties,
            CoinGeckoHttp http,
            CallBudget budget,
            Clock clock,
            CoinGeckoMetrics metrics,
            CircuitBreaker circuit) {
        this.properties = properties;
        this.http = http;
        this.budget = budget;
        this.clock = clock;
        this.metrics = metrics;
        this.circuit = circuit;
        this.chain = properties.network();
        this.parser = new ValueParser(CoinGeckoSource.REF, metrics::invalid);
        Gauge.builder("marketdata_active_tokens", this, adapter -> adapter.active.size())
                .register(metrics.registry());
        Gauge.builder("marketdata_oldest_price_age_seconds", this, CoinGeckoAdapter::oldestPriceAgeSeconds)
                .register(metrics.registry());
    }

    /** NFR4.2: 60 s window, at least 5 calls, 50% of TECHNICAL failures, 60 s open. */
    static CircuitBreaker defaultCircuit() {
        return CircuitBreaker.of(
                "marketdata-coingecko",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                        .slidingWindowSize(60)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(60))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .recordException(CoinGeckoAdapter::technical)
                        .ignoreException(failure -> !technical(failure))
                        .build());
    }

    private static boolean technical(Throwable failure) {
        return failure instanceof CoinGeckoCallException call && call.technical();
    }

    @Override
    public SourceRef source() {
        return CoinGeckoSource.REF;
    }

    @Override
    public boolean tolerantFailure() {
        return true;
    }

    @Override
    public synchronized FetchResult fetch(FetchRequest request) {
        Round round = new Round(clock.instant());
        Set<TokenId> watched = new LinkedHashSet<>(request.watched());
        if (notBefore != null && round.start.isBefore(notBefore)) {
            round.cut(FailureCategory.ERROR_RESPONSE);
            round.stop();
            log.warn("Market provider round skipped: the provider asked to wait (Retry-After)");
        }
        refreshActive(watched);

        Instant priceStart = clock.instant();
        priceTokens(round, priceable());
        metrics.round("price", Duration.between(priceStart, clock.instant()));

        if (discoveryDue(round.start) && round.left().toSeconds() >= DISCOVERY_MIN_LEFT_S && round.open()) {
            Instant discoveryStart = clock.instant();
            discover(round, watched);
            metrics.round("discovery", Duration.between(discoveryStart, clock.instant()));
            List<TokenId> unpriced =
                    priceable().stream().filter(t -> !round.priced.contains(t)).toList();
            priceTokens(round, unpriced);
        }

        if (round.left().toSeconds() >= DETAIL_MIN_LEFT_S && round.open()) {
            Instant detailStart = clock.instant();
            details(round);
            metrics.round("detail", Duration.between(detailStart, clock.instant()));
        }

        int activeCount = priceable().size();
        RoundSummary summary = new RoundSummary(activeCount, round.priced.size(), round.cut);
        List<TokenInfo> tokens = round.observed.stream()
                .map(infos::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        log.info(
                "Market provider round finished: source={}, active={}, priced={}, observations={}, cut={}",
                CoinGeckoSource.REF.sourceId(),
                activeCount,
                round.priced.size(),
                round.observations.size(),
                round.cut.map(Enum::name).orElse("NONE"));
        return new FetchResult(tokens, List.copyOf(round.observations), Optional.of(summary));
    }

    // ---------------------------------------------------------------------------------------------------- active set

    private void refreshActive(Set<TokenId> watched) {
        Set<TokenId> next = new LinkedHashSet<>(discovered.keySet());
        next.addAll(watched);
        Instant now = clock.instant();
        next.forEach(token -> enteredAt.putIfAbsent(token, now));
        enteredAt.keySet().retainAll(next);
        pricedAt.keySet().retainAll(next);
        active = Set.copyOf(next);
    }

    /** Active tokens whose symbol, name and decimals are known (only those can be sent to the collector). */
    private List<TokenId> priceable() {
        return active.stream()
                .filter(infos::containsKey)
                .sorted(Comparator.comparing(TokenId::mint))
                .toList();
    }

    private boolean discoveryDue(Instant now) {
        return lastDiscovery == null || !now.isBefore(lastDiscovery.plus(properties.discoveryInterval()));
    }

    // -------------------------------------------------------------------------------------------------------- prices

    private void priceTokens(Round round, List<TokenId> tokens) {
        for (int from = 0; from < tokens.size(); from += properties.batchSize()) {
            if (!round.open()) {
                // A batch left behind: the deadline (or a stop) cut the price round.
                round.cut(round.stopped ? FailureCategory.ERROR_RESPONSE : FailureCategory.TIMEOUT);
                return;
            }
            List<TokenId> batch = tokens.subList(from, Math.min(tokens.size(), from + properties.batchSize()));
            List<String> mints = batch.stream().map(TokenId::mint).toList();
            Optional<JsonNode> answer =
                    call(round, Kind.PRICE, Route.TOKEN_PRICES, () -> http.tokenPrices(chain, mints));
            if (answer.isEmpty()) {
                continue;
            }
            JsonNode attributes = answer.get().path("data").path("attributes");
            if (!attributes.isObject()) {
                log.warn("Market provider answer without attributes: route={}", Route.TOKEN_PRICES.label());
                continue;
            }
            Instant observedAt = clock.instant();
            for (TokenId token : batch) {
                MarketValue price = parser.amount(
                        MarketField.PRICE, attributes.path("token_prices").get(token.mint()), observedAt);
                round.add(token, MarketField.PRICE, price);
                round.add(
                        token,
                        MarketField.LIQUIDITY,
                        parser.amount(
                                MarketField.LIQUIDITY,
                                attributes.path("total_reserve_in_usd").get(token.mint()),
                                observedAt));
                round.add(
                        token,
                        MarketField.VOLUME_24H,
                        parser.amount(
                                MarketField.VOLUME_24H,
                                attributes.path("h24_volume_usd").get(token.mint()),
                                observedAt));
                if (price.isKnown()) {
                    round.priced.add(token);
                    pricedAt.put(token, observedAt);
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------- discovery

    private void discover(Round round, Set<TokenId> watched) {
        Optional<JsonNode> fresh = call(round, Kind.DISCOVERY, Route.NEW_POOLS, () -> http.newPools(chain));
        Optional<JsonNode> trending = round.open()
                ? call(round, Kind.DISCOVERY, Route.TRENDING_POOLS, () -> http.trendingPools(chain))
                : Optional.empty();
        if (fresh.isEmpty() && trending.isEmpty()) {
            return;
        }
        Map<TokenId, TokenInfo> found = new LinkedHashMap<>();
        fresh.ifPresent(answer -> collectPools(answer, found));
        trending.ifPresent(answer -> collectPools(answer, found));
        Map<TokenId, TokenInfo> limited = new LinkedHashMap<>();
        for (Map.Entry<TokenId, TokenInfo> entry : found.entrySet()) {
            if (limited.size() >= properties.discoveryLimit()) {
                break;
            }
            limited.put(entry.getKey(), entry.getValue());
        }
        boolean complete = fresh.isPresent() && trending.isPresent();
        if (complete) {
            discovered.clear();
            lastDiscovery = round.start;
        }
        limited.forEach((token, info) -> {
            if (complete || discovered.size() < properties.discoveryLimit()) {
                discovered.put(token, info);
            }
            infos.put(token, info);
        });
        refreshActive(watched);
        Set<TokenId> keep = new LinkedHashSet<>(active);
        infoAt.keySet().retainAll(keep);
        poolsAt.keySet().retainAll(keep);
        infoTriedAt.keySet().retainAll(keep);
        poolsTriedAt.keySet().retainAll(keep);
    }

    private void collectPools(JsonNode answer, Map<TokenId, TokenInfo> found) {
        Map<String, JsonNode> included = new LinkedHashMap<>();
        for (JsonNode item : answer.path("included")) {
            if ("token".equals(item.path("type").asText())) {
                included.put(item.path("id").asText(), item.path("attributes"));
            }
        }
        for (JsonNode pool : answer.path("data")) {
            String tokenId = pool.path("relationships")
                    .path("base_token")
                    .path("data")
                    .path("id")
                    .asText();
            JsonNode attributes = included.get(tokenId);
            if (attributes == null) {
                continue;
            }
            tokenInfo(attributes).ifPresent(info -> found.putIfAbsent(info.token(), info));
        }
    }

    /** Symbol, name and decimals of a token, checked like the port does, so a token is never discarded later. */
    private Optional<TokenInfo> tokenInfo(JsonNode attributes) {
        String mint = attributes.path("address").asText("");
        String symbol = attributes.path("symbol").asText("").trim();
        String name = attributes.path("name").asText("").trim();
        JsonNode decimals = attributes.path("decimals");
        Integer places = null;
        if (decimals.isIntegralNumber() && decimals.canConvertToInt()) {
            places = decimals.intValue();
        } else if (decimals.isTextual() && decimals.asText().trim().matches("\\d{1,2}")) {
            places = Integer.valueOf(decimals.asText().trim());
        }
        if (!MINT.matcher(mint).matches()
                || symbol.isEmpty()
                || symbol.length() > 32
                || name.length() > 120
                || hasControl(symbol)
                || hasControl(name)
                || places == null
                || places < 0
                || places > 18) {
            log.debug("Market provider token skipped: incomplete identity");
            return Optional.empty();
        }
        return Optional.of(new TokenInfo(new TokenId(chain, mint), symbol, name.isEmpty() ? null : name, places));
    }

    private static boolean hasControl(String text) {
        return text.codePoints().anyMatch(code -> {
            int type = Character.getType(code);
            return type == Character.CONTROL || type == Character.FORMAT;
        });
    }

    // ------------------------------------------------------------------------------------------------------- details

    private void details(Round round) {
        while (round.open() && round.left().toSeconds() >= DETAIL_MIN_LEFT_S) {
            Optional<DetailJob> job = nextDetail(round);
            if (job.isEmpty()) {
                return;
            }
            DetailJob next = job.get();
            Route route = next.info ? Route.TOKEN_INFO : Route.TOKEN_POOLS;
            Gate gate = gate(round, Kind.DETAIL, route);
            if (gate == Gate.CIRCUIT_OPEN) {
                onFailure(round, Kind.DETAIL, circuitOpen(route));
                return;
            }
            if (gate == Gate.DENIED) {
                return;
            }
            Supplier<JsonNode> call = next.info
                    ? () -> http.tokenInfo(chain, next.token.mint())
                    : () -> http.tokenPools(chain, next.token.mint());
            // The attempt moves the job to the back of the queue; only a success renews it (a transient failure is
            // tried again in a later round, after the other due jobs).
            (next.info ? infoTriedAt : poolsTriedAt).put(next.token, round.start);
            round.triedDetails.add(next);
            Optional<JsonNode> answer = counted(round, Kind.DETAIL, route, call);
            if (answer.isEmpty()) {
                continue;
            }
            (next.info ? infoAt : poolsAt).put(next.token, round.start);
            Instant observedAt = clock.instant();
            if (next.info) {
                JsonNode attributes = answer.get().path("data").path("attributes");
                if (!infos.containsKey(next.token)) {
                    tokenInfo(attributes).ifPresent(info -> infos.put(next.token, info));
                }
                if (infos.containsKey(next.token)) {
                    round.add(
                            next.token,
                            MarketField.HOLDERS,
                            parser.holders(attributes.path("holders").get("count"), observedAt));
                }
            } else if (infos.containsKey(next.token)) {
                JsonNode first = answer.get().path("data").path(0).path("attributes");
                round.add(
                        next.token, MarketField.CREATED_AT, parser.createdAt(first.get("pool_created_at"), observedAt));
            }
        }
    }

    /** A detail call of a token; {@code last} is its last attempt (never tried is the epoch). */
    private record DetailJob(TokenId token, boolean info, Instant last) {

        boolean sameAs(DetailJob other) {
            return token.equals(other.token) && info == other.info;
        }
    }

    /**
     * The detail call due the longest: due while it has no SUCCESS younger than {@code detailRefresh}; ordered by its
     * last attempt (never tried first; info before pools); watched tokens without info first. A job already tried in
     * this round is not tried again in it.
     */
    private Optional<DetailJob> nextDetail(Round round) {
        Instant now = round.start;
        List<DetailJob> due = new ArrayList<>();
        Instant never = Instant.EPOCH;
        for (TokenId token : active) {
            Instant info = infoAt.get(token);
            if (info == null || !now.isBefore(info.plus(properties.detailRefresh()))) {
                due.add(new DetailJob(token, true, infoTriedAt.getOrDefault(token, never)));
            }
            Instant pools = poolsAt.get(token);
            if (infos.containsKey(token) && (pools == null || !now.isBefore(pools.plus(properties.detailRefresh())))) {
                due.add(new DetailJob(token, false, poolsTriedAt.getOrDefault(token, never)));
            }
        }
        return due.stream()
                .filter(job -> round.triedDetails.stream().noneMatch(job::sameAs))
                .min(Comparator.comparing((DetailJob job) -> infos.containsKey(job.token) ? 1 : 0)
                        .thenComparing(DetailJob::last)
                        .thenComparing(job -> job.info ? 0 : 1)
                        .thenComparing(job -> job.token.mint()));
    }

    // --------------------------------------------------------------------------------------------------------- calls

    /** What the circuit and the budget answer for one call. */
    private enum Gate {
        GRANTED,
        CIRCUIT_OPEN,
        DENIED
    }

    /**
     * The circuit FIRST, then the budget: an open circuit never counts a call in the month nor in the minute. A call
     * the budget refuses gives its circuit permission back; a refused price call (or the conservative mode) cuts the
     * round.
     */
    private Gate gate(Round round, Kind kind, Route route) {
        if (!circuit.tryAcquirePermission()) {
            return Gate.CIRCUIT_OPEN;
        }
        Decision decision = budget.acquire(kind);
        if (decision.allowed()) {
            return Gate.GRANTED;
        }
        circuit.releasePermission();
        metrics.call(kind, Outcome.LIMITED);
        if (kind != Kind.DETAIL || decision == Decision.CONSERVATIVE) {
            log.warn("Market provider call not made: route={}, decision={}", route.label(), decision);
        }
        if (kind == Kind.PRICE || decision == Decision.CONSERVATIVE) {
            round.cut(FailureCategory.ERROR_RESPONSE);
        }
        return Gate.DENIED;
    }

    private static CoinGeckoCallException circuitOpen(Route route) {
        return CoinGeckoCallException.of(
                CoinGeckoCallException.Kind.CIRCUIT_OPEN, route.label(), "not called (circuit open)");
    }

    /** Asks the circuit and the budget, then makes the call (with its single retry). */
    private Optional<JsonNode> call(Round round, Kind kind, Route route, Supplier<JsonNode> call) {
        if (!round.open()) {
            return Optional.empty();
        }
        Gate gate = gate(round, kind, route);
        if (gate == Gate.CIRCUIT_OPEN) {
            onFailure(round, kind, circuitOpen(route));
            return Optional.empty();
        }
        if (gate == Gate.DENIED) {
            return Optional.empty();
        }
        return counted(round, kind, route, call);
    }

    /** One call already granted (circuit and budget), with one retry, granted again, when it still fits. */
    private Optional<JsonNode> counted(Round round, Kind kind, Route route, Supplier<JsonNode> call) {
        Attempt first = attempt(kind, call);
        if (first.failure.isEmpty()) {
            return Optional.ofNullable(first.answer);
        }
        CoinGeckoCallException failed = first.failure.get();
        if (failed.retryable() && round.open()) {
            Gate again = gate(round, kind, route);
            if (again == Gate.GRANTED) {
                metrics.call(kind, Outcome.RETRY);
                Attempt second = attempt(kind, call);
                if (second.failure.isEmpty()) {
                    return Optional.ofNullable(second.answer);
                }
                failed = second.failure.get();
            } else if (again == Gate.CIRCUIT_OPEN) {
                failed = circuitOpen(route);
            }
        }
        onFailure(round, kind, failed);
        return Optional.empty();
    }

    /** One call whose circuit permission is already held. */
    private Attempt attempt(Kind kind, Supplier<JsonNode> call) {
        try {
            JsonNode answer = call.get();
            circuit.onSuccess(0, TimeUnit.NANOSECONDS);
            metrics.call(kind, Outcome.OK);
            return new Attempt(answer, Optional.empty());
        } catch (CoinGeckoCallException failure) {
            circuit.onError(0, TimeUnit.NANOSECONDS, failure);
            return new Attempt(null, Optional.of(failure));
        }
    }

    private void onFailure(Round round, Kind kind, CoinGeckoCallException failure) {
        metrics.call(kind, Outcome.ERROR);
        log.warn("Market provider call failed: kind={}, failure={}", kind.label(), failure.getMessage());
        switch (failure.kind()) {
            case RATE_LIMITED -> {
                failure.retryAfter()
                        .ifPresent(wait -> notBefore = clock.instant().plus(wait));
                round.cut(FailureCategory.ERROR_RESPONSE);
                round.stop();
            }
            case CIRCUIT_OPEN -> {
                // No call is made while open; only a price round left behind changes the state of the source.
                if (kind == Kind.PRICE) {
                    round.cut(FailureCategory.ERROR_RESPONSE);
                }
                round.stop();
            }
            case TIMEOUT -> {
                if (kind == Kind.PRICE) {
                    round.cut(FailureCategory.TIMEOUT);
                }
            }
            default -> {
                if (kind == Kind.PRICE) {
                    round.cut(FailureCategory.ERROR_RESPONSE);
                }
            }
        }
    }

    /** Oldest age of the last price among the active tokens (a never priced token counts from its entry); -1 empty. */
    double oldestPriceAgeSeconds() {
        Instant now = clock.instant();
        long oldest = -1;
        for (TokenId token : active) {
            Instant at = pricedAt.getOrDefault(token, enteredAt.get(token));
            if (at != null) {
                oldest = Math.max(oldest, Duration.between(at, now).toSeconds());
            }
        }
        return oldest;
    }

    Set<TokenId> activeTokens() {
        return active;
    }

    private record Attempt(JsonNode answer, Optional<CoinGeckoCallException> failure) {}

    /** The state of one round. */
    private final class Round {

        private final Instant start;
        private final Set<TokenId> priced = new LinkedHashSet<>();
        private final Set<TokenId> observed = new LinkedHashSet<>();
        private final List<Observation> observations = new ArrayList<>();
        private final List<DetailJob> triedDetails = new ArrayList<>();
        private Optional<FailureCategory> cut = Optional.empty();
        private boolean stopped;

        private Round(Instant start) {
            this.start = start;
        }

        /** A new call may open: the round was not stopped and {@code lastCallStart} has not passed. */
        boolean open() {
            return !stopped && Duration.between(start, clock.instant()).compareTo(properties.lastCallStart()) < 0;
        }

        Duration left() {
            return properties.roundDeadline().minus(Duration.between(start, clock.instant()));
        }

        /** Keeps the first reason that cut the price round. */
        void cut(FailureCategory category) {
            if (cut.isEmpty()) {
                cut = Optional.of(category);
            }
        }

        void stop() {
            stopped = true;
        }

        void add(TokenId token, MarketField field, MarketValue value) {
            observations.add(new Observation(token, field, value));
            observed.add(token);
            metrics.observation(field);
        }
    }
}
