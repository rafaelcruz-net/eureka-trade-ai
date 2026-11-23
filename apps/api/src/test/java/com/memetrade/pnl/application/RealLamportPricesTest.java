package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, review R-06 (written before the fix): the price of a mint in lamports per base unit is
 * {@code tokenUsd x 10^9 / (solUsd x 10^decimals)}, 18 places, rounded down. A decimals value that is not a whole
 * number from 0 to 255 (fractional, negative, or too big) leaves ONLY that mint unknown; the other mints keep their
 * price and the block is never taken down by it.
 */
class RealLamportPricesTest {

    private static final SourceRef SOURCE = new SourceRef("coingecko", "CoinGecko", false);
    private static final String GOOD = "MintGood1111111111111111111111111111111111";
    private static final String HALF = "MintHalf1111111111111111111111111111111111";
    private static final String NEGATIVE = "MintNeg11111111111111111111111111111111111";
    private static final String HUGE = "MintHuge1111111111111111111111111111111111";

    private static MarketValue known(String value) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, Instant.parse("2026-11-23T12:00:00Z"), false);
    }

    private static TokenId token(String mint) {
        return new TokenId("solana", mint);
    }

    @Test
    void anInvalidDecimalsValueLeavesOnlyItsMintUnknown() {
        MarketDataQuery market = mock(MarketDataQuery.class);
        Map<TokenId, MarketValue> quotes = new HashMap<>();
        quotes.put(token(RealLamportPrices.WRAPPED_SOL), known("200"));
        for (String mint : Set.of(GOOD, HALF, NEGATIVE, HUGE)) {
            quotes.put(token(mint), known("2"));
        }
        when(market.referencePrices(any())).thenReturn(quotes);
        when(market.decimalsOf(any()))
                .thenReturn(Map.of(
                        token(GOOD), known("6"),
                        token(HALF), known("6.5"),
                        token(NEGATIVE), known("-1"),
                        token(HUGE), known("100000000000")));

        Map<String, Optional<BigDecimal>> prices =
                new RealLamportPrices(market).lamportPrices(Set.of(GOOD, HALF, NEGATIVE, HUGE));

        // 2 x 10^9 / (200 x 10^6) = 10 lamports per base unit.
        assertThat(prices.get(GOOD))
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("10"));
        assertThat(prices.get(HALF)).isEmpty();
        assertThat(prices.get(NEGATIVE)).isEmpty();
        assertThat(prices.get(HUGE)).isEmpty();
    }

    @Test
    void aMissingQuoteOrAnUnknownSolLeavesThePriceUnknownNeverZero() {
        MarketDataQuery market = mock(MarketDataQuery.class);
        when(market.referencePrices(any())).thenReturn(Map.of(token(GOOD), known("2")));
        when(market.decimalsOf(any())).thenReturn(Map.of(token(GOOD), known("6")));

        assertThat(new RealLamportPrices(market).lamportPrices(Set.of(GOOD)).get(GOOD))
                .isEmpty();
    }
}
