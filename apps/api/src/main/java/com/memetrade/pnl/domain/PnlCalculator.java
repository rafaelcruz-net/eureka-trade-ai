package com.memetrade.pnl.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The P&L of a period by token and in total (BR6.1, BR6.3, BR6.5, BR6.6). Pure. The totals are the sum of the lines,
 * already rounded, without a new rounding; one unknown line makes the total unrealized unknown and the report partial.
 * A line without an open position has a REAL zero unrealized (nothing is open), which is not an unknown market value.
 */
public final class PnlCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);

    private PnlCalculator() {}

    /**
     * One token of the period.
     *
     * @param realized sum of {@code Fill.realized} in the period
     * @param fees fees paid in the period, net of their compensations
     * @param openPosition the exit valuation of the open position now, if the token has one
     */
    public record TokenInput(
            String chain, String mint, BigDecimal realized, BigDecimal fees, Optional<Mark> openPosition) {}

    /** A known amount or an unknown one with its reason; never a zero standing for an unknown value. */
    public sealed interface Amount permits Amount.Known, Amount.Unknown {
        record Known(BigDecimal value) implements Amount {}

        record Unknown(Mark.UnrealizedUnknownReason reason) implements Amount {}
    }

    public record Line(
            String chain, String mint, BigDecimal realized, Amount unrealized, BigDecimal fees, boolean noLiquidity) {}

    public record Report(
            BigDecimal realized, Amount unrealized, BigDecimal fees, boolean partial, boolean empty, List<Line> lines) {
        public Report {
            lines = List.copyOf(lines);
        }
    }

    public static Report report(List<TokenInput> tokens) {
        List<Line> lines = new ArrayList<>();
        BigDecimal realized = ZERO;
        BigDecimal fees = ZERO;
        BigDecimal unrealized = ZERO;
        Optional<Mark.UnrealizedUnknownReason> firstUnknown = Optional.empty();
        for (TokenInput token : tokens) {
            Amount amount = unrealized(token.openPosition());
            if (amount instanceof Amount.Known known) {
                unrealized = unrealized.add(known.value());
            } else if (firstUnknown.isEmpty()) {
                firstUnknown = Optional.of(((Amount.Unknown) amount).reason());
            }
            boolean noLiquidity = token.openPosition()
                    .filter(mark -> mark instanceof Mark.Unvalued unvalued
                            && unvalued.reason() == Mark.UnrealizedUnknownReason.NO_LIQUIDITY)
                    .isPresent();
            lines.add(new Line(token.chain(), token.mint(), token.realized(), amount, token.fees(), noLiquidity));
            realized = realized.add(token.realized());
            fees = fees.add(token.fees());
        }
        Amount total = firstUnknown.<Amount>map(Amount.Unknown::new).orElse(new Amount.Known(unrealized));
        return new Report(realized, total, fees, firstUnknown.isPresent(), lines.isEmpty(), lines);
    }

    private static Amount unrealized(Optional<Mark> openPosition) {
        if (openPosition.isEmpty()) {
            return new Amount.Known(ZERO);
        }
        return switch (openPosition.get()) {
            case Mark.Valued valued -> new Amount.Known(valued.unrealized());
            case Mark.Unvalued unvalued -> new Amount.Unknown(unvalued.reason());
        };
    }
}
