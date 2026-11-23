package com.memetrade.pnl.application;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * BR6.2 of the 261113-trading-real-bot (moved here from {@code RealPnlService} by the U2 of 261121-paper-real-unico,
 * NFR1.13): the price of each mint in lamports per base unit, from the quotes of the market (M4):
 * {@code tokenUsd x 10^9 / (solUsd x 10^decimals)}, 18 places, rounded down. Any missing piece leaves the price unknown,
 * never zero. One read of the market per call; the P&L REAL calls it once, at the end, only for the mints with an open
 * position.
 */
@Component
public class RealLamportPrices {

    static final String WRAPPED_SOL = "So11111111111111111111111111111111111111112";
    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);

    /** The largest decimals value of a mint (an unsigned byte in the token program). */
    static final int MAX_DECIMALS = 255;

    private final MarketDataQuery market;

    public RealLamportPrices(MarketDataQuery market) {
        this.market = market;
    }

    /** Lamports per base unit of each mint asked; empty for an unknown price. */
    public Map<String, Optional<BigDecimal>> lamportPrices(Set<String> mints) {
        Map<String, Optional<BigDecimal>> prices = new HashMap<>();
        if (mints.isEmpty()) {
            return prices;
        }
        Set<TokenId> tokens = new HashSet<>();
        mints.forEach(mint -> tokens.add(new TokenId("solana", mint)));
        TokenId sol = new TokenId("solana", WRAPPED_SOL);
        Set<TokenId> withSol = new HashSet<>(tokens);
        withSol.add(sol);
        Map<TokenId, MarketValue> quotes = market.referencePrices(withSol);
        Map<TokenId, MarketValue> decimals = market.decimalsOf(tokens);
        Optional<BigDecimal> solUsd = known(quotes.get(sol)).filter(value -> value.signum() > 0);
        for (TokenId token : tokens) {
            Optional<BigDecimal> usd = known(quotes.get(token));
            Optional<Integer> places = known(decimals.get(token)).flatMap(RealLamportPrices::validDecimals);
            prices.put(
                    token.mint(),
                    usd.isEmpty() || places.isEmpty() || solUsd.isEmpty()
                            ? Optional.empty()
                            : Optional.of(usd.get()
                                    .multiply(LAMPORTS_PER_SOL)
                                    .divide(
                                            solUsd.get().multiply(BigDecimal.TEN.pow(places.get())),
                                            18,
                                            RoundingMode.DOWN)));
        }
        return prices;
    }

    /**
     * Review R-06: the decimals of a mint are a whole number from 0 to {@link #MAX_DECIMALS}; anything else (fractional,
     * negative, too big) leaves only the price of that mint unknown, never the whole block.
     */
    static Optional<Integer> validDecimals(BigDecimal value) {
        BigDecimal whole = value.stripTrailingZeros();
        if (whole.scale() > 0 || whole.signum() < 0 || whole.compareTo(BigDecimal.valueOf(MAX_DECIMALS)) > 0) {
            return Optional.empty();
        }
        return Optional.of(whole.intValueExact());
    }

    private static Optional<BigDecimal> known(MarketValue value) {
        return value instanceof MarketValue.Known known ? Optional.of(known.value()) : Optional.empty();
    }
}
