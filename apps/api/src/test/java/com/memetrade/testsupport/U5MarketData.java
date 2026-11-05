package com.memetrade.testsupport;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * C11 double of the U5 tests (unit-test-instructions.md "Mocks e dublês"): price, liquidity and decimals through the
 * {@link ControllableMarketData} of U7, plus the 5-minute windows per bucket with {@code unknown} on command. A token
 * the test never scripted falls back to the real U4 query. A failure can be injected on the next calls of the cycle
 * (technical failure of C11). No network access, nothing real.
 */
public final class U5MarketData implements MarketDataQuery {

    public static final Duration BUCKET = Duration.ofMinutes(5);

    private final ControllableMarketData values;
    private final MarketDataQuery real;
    private final Map<TokenId, List<PriceVolumeWindow.Point>> windows = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    public U5MarketData(MarketDataQuery real, Clock clock) {
        this.real = real;
        this.values = new ControllableMarketData(real, clock);
    }

    /** The price, liquidity and decimals double (known, unknown or stale on command). */
    public ControllableMarketData values() {
        return values;
    }

    public void clear() {
        values.clear();
        windows.clear();
        failure.set(null);
    }

    /** Every later call throws {@code failure} until {@link #clear()} (a technical failure of C11, BR3.5). */
    public void failWith(RuntimeException failure) {
        this.failure.set(failure);
    }

    /**
     * Known buckets ending at the last closed bucket of {@code slotStart} ({@code slotStart - 5 min}), oldest first:
     * {@code prices[i]} and {@code volumes[i]} are the price and the 24 h volume of bucket {@code b_(n-len+1+i)};
     * {@code null} makes that value UNKNOWN. Every value is observed 30 s before the bucket closes.
     */
    public void window(TokenId token, Instant slotStart, String[] prices, String[] volumes) {
        List<PriceVolumeWindow.Point> points = new ArrayList<>();
        Instant last = slotStart.minus(BUCKET);
        for (int i = 0; i < prices.length; i++) {
            Instant bucket = last.minus(BUCKET.multipliedBy(prices.length - 1L - i));
            Instant observed = bucket.plus(BUCKET).minusSeconds(30);
            points.add(new PriceVolumeWindow.Point(
                    bucket, value(prices[i], observed), value(volumes == null ? "1" : volumes[i], observed)));
        }
        windows.put(token, points);
    }

    /** A window made of the given points (for stale or missing buckets). */
    public void window(TokenId token, List<PriceVolumeWindow.Point> points) {
        windows.put(token, List.copyOf(points));
    }

    private static MarketValue value(String text, Instant observed) {
        if (text == null) {
            return ControllableMarketData.unknown();
        }
        return new MarketValue.Known(new BigDecimal(text), ControllableMarketData.SOURCE, observed, false);
    }

    @Override
    public MarketValue referencePrice(TokenId token) {
        failIfAsked();
        return values.referencePrice(token);
    }

    @Override
    public MarketValue liquidity(TokenId token) {
        failIfAsked();
        return values.liquidity(token);
    }

    @Override
    public MarketValue decimals(TokenId token) {
        failIfAsked();
        return values.decimals(token);
    }

    @Override
    public PriceVolumeWindow window(TokenId token, Duration length) {
        failIfAsked();
        List<PriceVolumeWindow.Point> points = windows.get(token);
        if (points == null) {
            return real.window(token, length);
        }
        Instant from = points.isEmpty() ? Instant.EPOCH : points.getFirst().bucketStart();
        Instant to = points.isEmpty() ? Instant.EPOCH : points.getLast().bucketStart();
        return new PriceVolumeWindow(token, from, to, points);
    }

    @Override
    public List<SourceStatus> sourceStatuses() {
        return real.sourceStatuses();
    }

    private void failIfAsked() {
        RuntimeException injected = failure.get();
        if (injected != null) {
            throw injected;
        }
    }
}
