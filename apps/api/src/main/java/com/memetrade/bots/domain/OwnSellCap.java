package com.memetrade.bots.domain;

import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 261121-paper-real-unico U4 (ENT-406; F3; BR1.4, BR1.5, BR3.1, NFR2.4, NFR2.6; security-design.md "Barreira 3"): the
 * cut of a SELL of the REAL cycle to the quantity available of the bot itself, a pure function, evaluated after the
 * engine decided and BEFORE the decision is recorded and goes to the queue:
 *
 * <ol>
 *   <li>not a SELL: not applicable (a buy is never cut);
 *   <li>no read before the lock ({@link OwnHoldings.NotRead}): unavailable, READ_FAILED;
 *   <li>the mark under the lock differs from the mark read before the snapshot: unavailable, STALE_SNAPSHOT (checked
 *       before the quantity: a decision of the bot was committed in between);
 *   <li>a read that failed: unavailable with its reason (defence in depth: the mints already left the request);
 *   <li>{@code requested = floor(quantity x 10^decimals)}, {@code sell = min(requested, available)}; zero is
 *       NO_OWN_QUANTITY, never a negative nor an order of zero.
 * </ol>
 *
 * Exact arithmetic only: an overflow is an {@link ArithmeticException} (a technical failure of the cycle), never a
 * truncated value.
 */
public final class OwnSellCap {

    private OwnSellCap() {}

    /** What the cycle does with the decision. */
    public sealed interface Verdict
            permits Verdict.NotApplicable, Verdict.Sell, Verdict.NoOwnQuantity, Verdict.Unavailable {

        /** Not a SELL: the cycle goes on as today. */
        record NotApplicable() implements Verdict {}

        /** Sell {@code sellBaseUnits}; {@code capped} when it is below the request. */
        record Sell(long sellBaseUnits, long requestedBaseUnits, boolean capped) implements Verdict {

            public Sell {
                if (sellBaseUnits < 1 || sellBaseUnits > requestedBaseUnits) {
                    throw new IllegalArgumentException("a cut sale sells at least 1 and never more than asked");
                }
            }
        }

        /** Nothing of its own available: the outcome REAL_NO_OWN_QUANTITY. */
        record NoOwnQuantity(long requestedBaseUnits) implements Verdict {}

        /** The own quantity is not known: the outcome REAL_OWN_QUANTITY_UNAVAILABLE with the reason. */
        record Unavailable(OwnQuantityReason reason) implements Verdict {

            public Unavailable {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * @param m0 the mark of the bot read before the snapshot (ignored for {@link OwnHoldings.NotRead})
     * @param m1 the mark of the bot read under the lock of the cycle
     * @param decimals the decimals of the mint of the decision
     */
    public static Verdict verdict(DecisionOutcome outcome, OwnHoldings holdings, long m0, long m1, int decimals) {
        if (outcome.action() != DecisionAction.SELL) {
            return new Verdict.NotApplicable();
        }
        if (holdings instanceof OwnHoldings.NotRead) {
            return new Verdict.Unavailable(OwnQuantityReason.READ_FAILED);
        }
        if (m1 != m0) {
            return new Verdict.Unavailable(OwnQuantityReason.STALE_SNAPSHOT);
        }
        if (holdings instanceof OwnHoldings.Unavailable unavailable) {
            return new Verdict.Unavailable(unavailable.reason());
        }
        long requested = requestedBaseUnits(outcome.requestedQuantity().orElseThrow(), decimals);
        long available = ((OwnHoldings.Known) holdings)
                .available(outcome.token().orElseThrow().mint());
        long sell = Math.min(requested, available);
        if (sell <= 0) {
            return new Verdict.NoOwnQuantity(requested);
        }
        return new Verdict.Sell(sell, requested, sell < requested);
    }

    /** {@code floor(quantity x 10^decimals)} in base units; an overflow of {@code long} throws. */
    public static long requestedBaseUnits(BigDecimal quantity, int decimals) {
        return quantity.movePointRight(decimals).setScale(0, RoundingMode.DOWN).longValueExact();
    }
}
