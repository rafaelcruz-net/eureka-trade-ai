package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import java.math.BigDecimal;
import java.time.Instant;

/** Outcome of {@link FillCalculator}: the computed fill with every hypothesis, or the reason it is not filled. */
public sealed interface FillQuote permits FillQuote.Computed, FillQuote.Refused {

    /**
     * @param requestedQuantity the quantity as requested, before the truncation (recorded in the order)
     * @param truncatedRequested the requested quantity truncated to the mint decimals
     * @param filledQuantity after the position limit (sell) and the liquidity cap; greater than zero
     * @param partial the filled quantity is below the truncated requested one (BR1.4, BR1.5)
     * @param referenceAmount {@code money(filled x reference)}: the TRADE_COST or TRADE_PROCEEDS (BR2.2)
     * @param grossAmount {@code money(filled x fillPrice)}
     * @param fee {@code money(gross x feePct / 100)}
     */
    record Computed(
            BigDecimal requestedQuantity,
            BigDecimal truncatedRequested,
            BigDecimal filledQuantity,
            boolean partial,
            int decimals,
            BigDecimal referencePrice,
            BigDecimal quotedPrice,
            BigDecimal fillPrice,
            BigDecimal spreadPct,
            BigDecimal impactPct,
            BigDecimal slippagePct,
            BigDecimal consumedPct,
            BigDecimal liquidity,
            BigDecimal referenceAmount,
            BigDecimal grossAmount,
            BigDecimal fee,
            String marketSource,
            Instant marketObservedAt)
            implements FillQuote {}

    record Refused(NotFilledReason reason) implements FillQuote {}
}
