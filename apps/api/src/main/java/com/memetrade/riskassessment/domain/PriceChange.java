package com.memetrade.riskassessment.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Input of PRICE_CHANGE_1H (BR1.10): {@code |current - previous| / previous x 100} in percentage points, rounded to
 * scale 4 half up; the curve uses the rounded value. The current price is the displayed PRICE (BR1.2); the previous is
 * the KNOWN PRICE sample of 60 to 65 minutes ago. The result carries the source and observation time of the current
 * price.
 *
 * <ul>
 *   <li>current price UNKNOWN: UNKNOWN with the same reason;
 *   <li>no sample in the window: {@code Unknown(NOT_YET_OBSERVED)};
 *   <li>previous price 0 (division by zero): {@code Unknown(NOT_PROVIDED)} with the source of the sample, never zero.
 * </ul>
 */
public final class PriceChange {

    static final int SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PriceChange() {}

    public static MarketValue of(MarketValue current, MarketValue previous) {
        if (!(current instanceof MarketValue.Known now)) {
            return current;
        }
        if (!(previous instanceof MarketValue.Known before)) {
            return new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty());
        }
        if (before.value().signum() == 0) {
            return new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(before.source()));
        }
        BigDecimal change = now.value()
                .subtract(before.value())
                .abs()
                .multiply(HUNDRED)
                .divide(before.value(), SCALE, RoundingMode.HALF_UP);
        return new MarketValue.Known(change, now.source(), now.observedAt(), now.stale());
    }
}
