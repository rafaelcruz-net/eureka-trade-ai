package com.memetrade.ledger.domain;

import com.memetrade.marketdata.api.MarketValue;
import java.time.Instant;
import java.util.Objects;

/**
 * What the fill and the marking read from C11 (BR1.10): reference price and liquidity in USDC, the mint decimals, and
 * the instant of the reading (the injected clock), so the calculation stays pure and decides staleness itself.
 */
public record MarketInputs(MarketValue referencePrice, MarketValue liquidity, MarketValue decimals, Instant now) {

    public MarketInputs {
        Objects.requireNonNull(referencePrice, "referencePrice");
        Objects.requireNonNull(liquidity, "liquidity");
        Objects.requireNonNull(decimals, "decimals");
        Objects.requireNonNull(now, "now");
    }
}
