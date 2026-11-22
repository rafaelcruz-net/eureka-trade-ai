package com.memetrade.pnl.api;

import java.util.Objects;

/**
 * 261121-paper-real-unico U1, C10: a REAL amount in lamports, or unknown with its reason; never a zero standing for an
 * unknown value (FR3.5, FR3.10).
 */
public sealed interface LamportsValue permits LamportsValue.Known, LamportsValue.Unknown {

    /** Why an amount is unknown. */
    enum UnknownReason {
        PRICE_UNKNOWN,
        STALE,
        NO_LIQUIDITY
    }

    record Known(long lamports) implements LamportsValue {}

    record Unknown(UnknownReason reason) implements LamportsValue {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
