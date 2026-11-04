package com.memetrade.pnl.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * C13, U7 for U6: the risk view of a bot of the person of the authentication context, read inside the cycle (F6). Read
 * only. Exposure is the cost of the open positions (BR7.1), always known; the day loss is read directly from the fills
 * and fees of the UTC day, never from the daily summary (BR7.2); the equity is EMPTY when a price is not reliable,
 * never zero (BR7.3, BR7.4).
 */
public interface PortfolioRiskView {

    /**
     * @throws TenantMismatchException when the bot's PAPER account belongs to another person or tenant (BR9.1)
     * @throws IllegalStateException when the bot has no PAPER account (U5 did not call {@code fund})
     */
    RiskSnapshot snapshot(UUID botId, String chain, String tokenMint, LocalDate utcDay);

    /**
     * @param tokenExposure cost basis of the evaluated token's position (zero without a position)
     * @param totalExposure sum of the cost basis of the bot's positions
     * @param realizedDayLossWithFees max(0, -(realized - fees)) of the fills of the UTC day
     * @param equity balance plus the net exit value of every open position; empty when unknown
     * @param peakEquity the highest equity reached (starts at the FUNDING amount)
     * @param availableVirtualBalance the sum of the entries (BR2.5)
     * @param lastTradeAt the last filled order of the bot
     */
    record RiskSnapshot(
            BigDecimal tokenExposure,
            BigDecimal totalExposure,
            BigDecimal realizedDayLossWithFees,
            Optional<BigDecimal> equity,
            BigDecimal peakEquity,
            BigDecimal availableVirtualBalance,
            Optional<Instant> lastTradeAt) {}

    /** Domain refusal of C13 (BR9.1): the bot is not the context person's; audited as {@code REQUEST_FORBIDDEN}. */
    final class TenantMismatchException extends RuntimeException {
        public TenantMismatchException(UUID botId) {
            super("The PAPER account of bot " + botId + " does not belong to the person of the context");
        }
    }
}
