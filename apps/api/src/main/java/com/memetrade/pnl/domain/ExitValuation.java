package com.memetrade.pnl.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.pnl.domain.Mark.UnrealizedUnknownReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * The estimated exit value of a position now (BR6.2), with the spread and the fee of the policy in force. Pure. The
 * whole position is valued with its whole consumption of the liquidity (up to 100%), so the unrealized result is
 * conservative. A price that is not KNOWN, a stale price (flag or older than 5 minutes) or a liquidity that is not a
 * KNOWN value above zero make the mark unknown with its reason (BR6.3, BR6.4), never zero.
 */
public final class ExitValuation {

    private static final int MONEY = 6;
    private static final int PRICE = 12;
    private static final int PERCENT = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Duration MAX_PRICE_AGE = Duration.ofMinutes(5);

    private final BigDecimal spreadPct;
    private final BigDecimal feePct;

    public ExitValuation(BigDecimal spreadPct, BigDecimal feePct) {
        this.spreadPct = spreadPct;
        this.feePct = feePct;
    }

    public Mark value(
            BigDecimal quantity, BigDecimal costBasis, MarketValue price, MarketValue liquidity, Instant now) {
        if (!(price instanceof MarketValue.Known reference) || reference.value().signum() <= 0) {
            return new Mark.Unvalued(UnrealizedUnknownReason.PRICE_UNKNOWN);
        }
        if (reference.stale() || Duration.between(reference.observedAt(), now).compareTo(MAX_PRICE_AGE) > 0) {
            return new Mark.Unvalued(UnrealizedUnknownReason.STALE);
        }
        if (!(liquidity instanceof MarketValue.Known pool) || pool.value().signum() <= 0) {
            return new Mark.Unvalued(UnrealizedUnknownReason.NO_LIQUIDITY);
        }
        BigDecimal ref = reference.value().setScale(PRICE, RoundingMode.HALF_EVEN);
        BigDecimal consumed = quantity.multiply(ref)
                .multiply(HUNDRED)
                .divide(pool.value(), PERCENT, RoundingMode.HALF_EVEN)
                .min(HUNDRED.setScale(PERCENT));
        BigDecimal exit = ref.multiply(
                        BigDecimal.ONE.subtract(spreadPct.add(consumed).divide(HUNDRED)))
                .setScale(PRICE, RoundingMode.HALF_EVEN);
        if (exit.signum() < 0) {
            exit = BigDecimal.ZERO.setScale(PRICE);
        }
        BigDecimal gross = quantity.multiply(exit).setScale(MONEY, RoundingMode.HALF_EVEN);
        BigDecimal fee = gross.multiply(feePct).divide(HUNDRED).setScale(MONEY, RoundingMode.HALF_EVEN);
        BigDecimal net = gross.subtract(fee);
        return new Mark.Valued(
                consumed, exit, gross, fee, net, net.subtract(costBasis).setScale(MONEY));
    }
}
