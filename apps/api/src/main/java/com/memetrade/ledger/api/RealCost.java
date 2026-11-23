package com.memetrade.ledger.api;

import java.util.Objects;

/**
 * 261121-paper-real-unico U2 (decision F-3 of the founder, C11, ENT-206): the cost of the open quantity of a REAL
 * position, explicit like the {@code unknown} of the market data: {@link Known} lamports, never negative, or
 * {@link Unknown} with its reason. There is no conversion of an unknown cost into 0 nor into {@code null}: every reader
 * handles the two cases ({@code switch} over the sealed type).
 */
public sealed interface RealCost permits RealCost.Known, RealCost.Unknown {

    /** A known cost in lamports (BR2.1, BR2.2), at least 0. */
    record Known(long lamports) implements RealCost {
        public Known {
            if (lamports < 0) {
                throw new IllegalArgumentException("a cost is never negative");
            }
        }
    }

    /** A cost that is not known, with why. */
    record Unknown(RealCostUnknownReason reason) implements RealCost {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
