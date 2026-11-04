package com.memetrade.ledger.loadtest;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketListingQuery.Filters;
import com.memetrade.marketdata.api.MarketListingQuery.ListingRequest;
import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: the simulated tokens of U4 the load bots trade, read only through the
 * public interfaces of MarketData ({@link MarketListingQuery} for the list, {@link MarketDataQuery}, C11, for price,
 * liquidity and decimals). It never reads a MarketData table. Quantities are exact decimals truncated to the decimals
 * of the mint; a token with an unknown or stale value is simply not traded.
 */
@Component
@Profile("loadtest")
public class LoadtestTokens {

    /** USDC per simulated trade: far below the 10% liquidity ceiling of the simulation policy (BR1.4). */
    static final BigDecimal NOTIONAL = new BigDecimal("10");

    private static final BigDecimal MIN_LIQUIDITY = new BigDecimal("10000");
    private static final int PAGE = 100;

    private final MarketListingQuery listing;
    private final MarketDataQuery market;
    private final Clock clock;

    public LoadtestTokens(MarketListingQuery listing, MarketDataQuery market, Clock clock) {
        this.listing = listing;
        this.market = market;
        this.clock = clock;
    }

    /** Up to {@code wanted} tokens, by liquidity, whose price, liquidity and decimals are known and fresh now. */
    public List<TokenId> tradable(int wanted) {
        Filters filters = new Filters(
                Optional.of(MIN_LIQUIDITY),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        ListingRequest request = new ListingRequest(
                clock.instant(), filters, SortField.LIQUIDITY, SortOrder.DESC, Optional.empty(), PAGE);
        return listing.list(request).tokens().stream()
                .map(MarketListingQuery.ListedToken::token)
                .filter(token -> quantity(token).isPresent())
                .limit(wanted)
                .toList();
    }

    /** The quantity worth {@link #NOTIONAL} USDC now, truncated to the mint decimals; empty when not tradable. */
    public Optional<BigDecimal> quantity(TokenId token) {
        Optional<BigDecimal> price = fresh(market.referencePrice(token));
        Optional<BigDecimal> liquidity = fresh(market.liquidity(token));
        if (price.isEmpty()
                || price.get().signum() <= 0
                || liquidity.isEmpty()
                || liquidity.get().signum() <= 0) {
            return Optional.empty();
        }
        if (!(market.decimals(token) instanceof MarketValue.Known decimals)) {
            return Optional.empty();
        }
        BigDecimal quantity = NOTIONAL.divide(price.get(), decimals.value().intValueExact(), RoundingMode.DOWN);
        return quantity.signum() > 0 ? Optional.of(quantity) : Optional.empty();
    }

    private static Optional<BigDecimal> fresh(MarketValue value) {
        return value instanceof MarketValue.Known known && !known.stale()
                ? Optional.of(known.value())
                : Optional.empty();
    }
}
