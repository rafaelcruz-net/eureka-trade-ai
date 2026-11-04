package com.memetrade.testsupport;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C11 double of the U7 tests: price, liquidity and decimals of each synthetic token are set by the test (known,
 * {@code unknown} or stale); a token the test never scripted falls back to the real U4 query. No network access.
 */
public final class ControllableMarketData implements MarketDataQuery {

    public static final SourceRef SOURCE = new SourceRef("sim-u7", "Simulado U7", true);

    private final MarketDataQuery real;
    private final Clock clock;
    private final Map<TokenId, Script> scripts = new ConcurrentHashMap<>();

    public ControllableMarketData(MarketDataQuery real, Clock clock) {
        this.real = real;
        this.clock = clock;
    }

    /** Known price, liquidity and decimals observed now. */
    public void set(TokenId token, String price, String liquidity, int decimals) {
        Instant now = clock.instant();
        scripts.put(token, new Script(known(price, now), known(liquidity, now), known(String.valueOf(decimals), now)));
    }

    /** Known values observed {@code age} ago (stale above 5 minutes, BR1.2). */
    public void observedAgo(TokenId token, String price, String liquidity, int decimals, Duration age) {
        Instant at = clock.instant().minus(age);
        boolean stale = age.compareTo(Duration.ofMinutes(5)) > 0;
        scripts.put(
                token,
                new Script(
                        new MarketValue.Known(new BigDecimal(price), SOURCE, at, stale),
                        new MarketValue.Known(new BigDecimal(liquidity), SOURCE, at, stale),
                        new MarketValue.Known(BigDecimal.valueOf(decimals), SOURCE, at, false)));
    }

    public void price(TokenId token, MarketValue price) {
        scripts.compute(token, (k, s) -> (s == null ? Script.unknown() : s).withPrice(price));
    }

    public void liquidity(TokenId token, MarketValue liquidity) {
        scripts.compute(token, (k, s) -> (s == null ? Script.unknown() : s).withLiquidity(liquidity));
    }

    public void decimals(TokenId token, MarketValue decimals) {
        scripts.compute(token, (k, s) -> (s == null ? Script.unknown() : s).withDecimals(decimals));
    }

    public void clear() {
        scripts.clear();
    }

    public static MarketValue.Unknown unknown() {
        return new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.of(SOURCE));
    }

    public MarketValue.Known known(String value) {
        return known(value, clock.instant());
    }

    private static MarketValue.Known known(String value, Instant at) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, at, false);
    }

    @Override
    public MarketValue referencePrice(TokenId token) {
        Script script = scripts.get(token);
        return script == null ? real.referencePrice(token) : script.price();
    }

    @Override
    public MarketValue liquidity(TokenId token) {
        Script script = scripts.get(token);
        return script == null ? real.liquidity(token) : script.liquidity();
    }

    @Override
    public MarketValue decimals(TokenId token) {
        Script script = scripts.get(token);
        return script == null ? real.decimals(token) : script.decimals();
    }

    @Override
    public PriceVolumeWindow window(TokenId token, Duration length) {
        return real.window(token, length);
    }

    @Override
    public List<SourceStatus> sourceStatuses() {
        return real.sourceStatuses();
    }

    private record Script(MarketValue price, MarketValue liquidity, MarketValue decimals) {
        static Script unknown() {
            return new Script(
                    ControllableMarketData.unknown(),
                    ControllableMarketData.unknown(),
                    ControllableMarketData.unknown());
        }

        Script withPrice(MarketValue value) {
            return new Script(value, liquidity, decimals);
        }

        Script withLiquidity(MarketValue value) {
            return new Script(price, value, decimals);
        }

        Script withDecimals(MarketValue value) {
            return new Script(price, liquidity, value);
        }
    }
}
