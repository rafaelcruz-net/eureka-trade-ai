package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.marketdata.api.MarketValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;

/**
 * Pure simulation of a PAPER fill (BR1.2 to BR1.8, BR2.5, BR5.1). Reads neither clock nor database: the instant of
 * the market reading comes in {@link MarketInputs}. Percentages are points with 4 decimals, unit prices 12, USDC 6,
 * each rounded once from exact values; quantities are truncated to the mint decimals.
 */
public final class FillCalculator {

    /** BR1.2: a price observed more than 5 minutes ago is not reliable. */
    public static final Duration MAX_PRICE_AGE = Duration.ofMinutes(5);

    private final SimulationPolicy policy;

    public FillCalculator(SimulationPolicy policy) {
        this.policy = policy;
    }

    public SimulationPolicy policy() {
        return policy;
    }

    /**
     * A TRADE order: price and liquidity reliable (BR1.2), truncation (BR1.7), position limit of a sell (BR1.5), the
     * 10% liquidity cap (BR1.4), the fill price (BR1.3), the fee (BR1.6) and, for a buy, the balance (BR2.5).
     */
    public FillQuote quote(
            OrderSide side,
            BigDecimal requestedQuantity,
            BigDecimal positionQuantity,
            BigDecimal availableBalance,
            MarketInputs market) {
        Optional<NotFilledReason> unreliable = unreliable(market);
        if (unreliable.isPresent()) {
            return new FillQuote.Refused(unreliable.get());
        }
        BigDecimal reference = Scales.price(((MarketValue.Known) market.referencePrice()).value());
        BigDecimal liquidity = ((MarketValue.Known) market.liquidity()).value();
        int decimals = ((MarketValue.Known) market.decimals()).value().intValueExact();

        BigDecimal truncated = Scales.quantity(requestedQuantity, decimals);
        if (side == OrderSide.SELL && positionQuantity.signum() <= 0) {
            return new FillQuote.Refused(NotFilledReason.NO_POSITION);
        }
        BigDecimal considered = side == OrderSide.SELL ? truncated.min(positionQuantity) : truncated;
        BigDecimal filled = Scales.quantity(capped(considered, reference, liquidity, decimals), decimals);
        if (filled.signum() <= 0) {
            return new FillQuote.Refused(NotFilledReason.QUANTITY_BELOW_MINIMUM);
        }

        BigDecimal consumed = Scales.percent(filled.multiply(reference)
                .multiply(Scales.HUNDRED)
                .divide(liquidity, Scales.PERCENT, RoundingMode.HALF_EVEN));
        BigDecimal impact = Scales.percent(consumed.multiply(policy.impactPerConsumedPct()));
        BigDecimal slippage = Scales.percent(policy.spreadPct().add(impact));
        BigDecimal direction = side == OrderSide.BUY ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal fillPrice = floorAtZero(Scales.price(reference.multiply(
                BigDecimal.ONE.add(direction.multiply(slippage).divide(Scales.HUNDRED)))));
        BigDecimal quoted = Scales.price(reference.multiply(
                BigDecimal.ONE.add(direction.multiply(policy.spreadPct()).divide(Scales.HUNDRED))));
        BigDecimal gross = Scales.money(filled.multiply(fillPrice));
        if (gross.signum() <= 0) {
            return new FillQuote.Refused(NotFilledReason.QUANTITY_BELOW_MINIMUM);
        }
        BigDecimal fee = fee(gross);
        if (side == OrderSide.BUY && gross.add(fee).compareTo(availableBalance) > 0) {
            return new FillQuote.Refused(NotFilledReason.INSUFFICIENT_BALANCE);
        }
        return new FillQuote.Computed(
                requestedQuantity,
                truncated,
                filled,
                filled.compareTo(truncated) < 0,
                decimals,
                reference,
                quoted,
                fillPrice,
                policy.spreadPct(),
                impact,
                slippage,
                consumed,
                liquidity,
                Scales.money(filled.multiply(reference)),
                gross,
                fee,
                sourceOf(market.referencePrice()),
                ((MarketValue.Known) market.referencePrice()).observedAt());
    }

    /**
     * A CLOSE at the reference price (BR5.1): value {@code money(quantity x reference)}, the policy fee on it, no
     * spread, no impact, no cap. Like every fill it needs a reliable price and a liquidity above zero (BR1.2); without
     * them the position waits in CLOSE_PENDING (BR5.2).
     */
    public FillQuote closeQuote(BigDecimal quantity, MarketInputs market) {
        Optional<NotFilledReason> unreliable = unreliablePriceOrLiquidity(market);
        if (unreliable.isPresent()) {
            return new FillQuote.Refused(unreliable.get());
        }
        BigDecimal reference = Scales.price(((MarketValue.Known) market.referencePrice()).value());
        BigDecimal liquidity = ((MarketValue.Known) market.liquidity()).value();
        BigDecimal value = Scales.money(quantity.multiply(reference));
        BigDecimal zeroPct = Scales.percent(BigDecimal.ZERO);
        return new FillQuote.Computed(
                quantity,
                quantity,
                quantity,
                false,
                quantity.scale(),
                reference,
                reference,
                reference,
                zeroPct,
                zeroPct,
                zeroPct,
                zeroPct,
                liquidity,
                value,
                value,
                fee(value),
                sourceOf(market.referencePrice()),
                ((MarketValue.Known) market.referencePrice()).observedAt());
    }

    /** BR1.6: {@code money(gross x feePct / 100)}. */
    public BigDecimal fee(BigDecimal gross) {
        return Scales.money(gross.multiply(policy.feePct()).divide(Scales.HUNDRED));
    }

    /** BR1.2: the price is KNOWN, not flagged stale and at most 5 minutes old at the reading. */
    public static boolean isReliable(MarketValue price, java.time.Instant now) {
        return price instanceof MarketValue.Known known
                && !known.stale()
                && Duration.between(known.observedAt(), now).compareTo(MAX_PRICE_AGE) <= 0;
    }

    private Optional<NotFilledReason> unreliable(MarketInputs market) {
        Optional<NotFilledReason> priceOrLiquidity = unreliablePriceOrLiquidity(market);
        if (priceOrLiquidity.isPresent()) {
            return priceOrLiquidity;
        }
        if (!(market.decimals() instanceof MarketValue.Known)) {
            return Optional.of(NotFilledReason.PRICE_UNAVAILABLE);
        }
        return Optional.empty();
    }

    private static Optional<NotFilledReason> unreliablePriceOrLiquidity(MarketInputs market) {
        if (!isReliable(market.referencePrice(), market.now())
                || ((MarketValue.Known) market.referencePrice()).value().signum() <= 0) {
            return Optional.of(NotFilledReason.PRICE_UNAVAILABLE);
        }
        if (!(market.liquidity() instanceof MarketValue.Known liquidity)
                || liquidity.value().signum() <= 0) {
            return Optional.of(NotFilledReason.NO_LIQUIDITY);
        }
        return Optional.empty();
    }

    /** BR1.4: above the cap, the filled quantity is the cap value divided by the reference, truncated. */
    private BigDecimal capped(BigDecimal considered, BigDecimal reference, BigDecimal liquidity, int decimals) {
        BigDecimal maxValue =
                liquidity.multiply(policy.maxLiquidityConsumptionPct()).divide(Scales.HUNDRED);
        if (considered.multiply(reference).compareTo(maxValue) <= 0) {
            return considered;
        }
        return maxValue.divide(reference, decimals, RoundingMode.DOWN);
    }

    private static BigDecimal floorAtZero(BigDecimal price) {
        return price.signum() < 0 ? Scales.price(BigDecimal.ZERO) : price;
    }

    private static String sourceOf(MarketValue value) {
        return ((MarketValue.Known) value).source().sourceId();
    }
}
