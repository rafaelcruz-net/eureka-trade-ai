package com.memetrade.pnl.application;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.real.RealPnlCalculator;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.api.RealSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * BR6.2 (U4-NFR8.4): the P&L REAL of a person, from the orders settled on the network (M6: the input, the output read
 * from the network, the fee and who paid it) and the quotes of the market (M4), never from a PAPER table. A failure on
 * chain counts only its fee. The quote in dollars becomes lamports per base unit with the quote of the wrapped SOL of
 * the same batch and the decimals of the mint; any missing piece leaves the price unknown, never zero.
 */
@Service
public class RealPnlService {

    static final String WRAPPED_SOL = "So11111111111111111111111111111111111111112";
    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);

    private final RealExecutionPort execution;
    private final MarketDataQuery market;

    public RealPnlService(RealExecutionPort execution, MarketDataQuery market) {
        this.execution = execution;
        this.market = market;
    }

    public RealPnlCalculator.Result pnl(UUID tenantId, UUID userId) {
        List<RealPnlCalculator.Fill> fills = new ArrayList<>();
        Set<String> mints = new HashSet<>();
        for (RealOrderView order : execution.settledOrders(tenantId, userId)) {
            long fee = order.feeLamports().orElse(0L);
            if (order.state() != RealOrderState.CONFIRMED) {
                fills.add(new RealPnlCalculator.Fill(order.mint(), false, 0, 0, fee));
                continue;
            }
            mints.add(order.mint());
            long out = order.outBaseUnits().orElse(0L);
            fills.add(
                    order.side() == RealSide.BUY
                            ? new RealPnlCalculator.Fill(
                                    order.mint(),
                                    true,
                                    order.inputLamports().orElse(0L),
                                    out,
                                    fee,
                                    order.rentLamports().orElse(0L))
                            : new RealPnlCalculator.Fill(
                                    order.mint(),
                                    false,
                                    out,
                                    order.inputBaseUnits().orElse(0L),
                                    fee));
        }
        return RealPnlCalculator.of(fills, lamportPrices(mints));
    }

    /** Lamports per base unit of each mint: {@code tokenUsd x 10^9 / (solUsd x 10^decimals)}, 18 places, down. */
    private Map<String, Optional<BigDecimal>> lamportPrices(Set<String> mints) {
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
            Optional<BigDecimal> places = known(decimals.get(token));
            prices.put(
                    token.mint(),
                    usd.isEmpty() || places.isEmpty() || solUsd.isEmpty()
                            ? Optional.empty()
                            : Optional.of(usd.get()
                                    .multiply(LAMPORTS_PER_SOL)
                                    .divide(
                                            solUsd.get()
                                                    .multiply(BigDecimal.TEN.pow(
                                                            places.get().intValueExact())),
                                            18,
                                            RoundingMode.DOWN)));
        }
        return prices;
    }

    private static Optional<BigDecimal> known(MarketValue value) {
        return value instanceof MarketValue.Known known ? Optional.of(known.value()) : Optional.empty();
    }
}
