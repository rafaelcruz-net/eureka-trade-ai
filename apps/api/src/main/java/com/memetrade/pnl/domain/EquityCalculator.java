package com.memetrade.pnl.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Equity and equity peak (BR7.3). Pure. */
public final class EquityCalculator {

    private EquityCalculator() {}

    /** balance + the net exit value of every open position; empty when any of them is unknown (never zero). */
    public static Optional<BigDecimal> equity(BigDecimal balance, List<Mark> openPositions) {
        BigDecimal equity = balance;
        for (Mark mark : openPositions) {
            if (!(mark instanceof Mark.Valued valued)) {
                return Optional.empty();
            }
            equity = equity.add(valued.netExitValue());
        }
        return Optional.of(equity);
    }

    /** The peak after an equity: it only rises, and an unknown equity keeps it. */
    public static BigDecimal nextPeak(BigDecimal currentPeak, Optional<BigDecimal> equity) {
        return equity.filter(value -> value.compareTo(currentPeak) > 0).orElse(currentPeak);
    }
}
