package com.memetrade.marketdata.api;

import java.time.Instant;
import java.util.List;

/** Price and 24 h volume of a token per 5-minute UTC bucket (C11); a bucket without a sample is UNKNOWN. */
public record PriceVolumeWindow(TokenId token, Instant from, Instant to, List<Point> points) {

    public PriceVolumeWindow {
        points = List.copyOf(points);
    }

    public record Point(Instant bucketStart, MarketValue price, MarketValue volume24h) {}
}
