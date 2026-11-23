package com.memetrade.riskcontrol.real.domain;

import com.memetrade.ledger.api.RealCostUnknownReason;
import java.util.Objects;

/**
 * 261121-paper-real-unico U2 (decision F-3, BR5.2 of the barrier): the value in lamports of the open positions of a
 * wallet (or of the wallet), {@link Known} or {@link Unknown}: a position without a price is worth its cost, and with an
 * unknown cost the value is unknown, never zero. A buy never gets here with an unknown value (a position without a
 * usable price refuses it first); the rounds handle both cases (F-4).
 */
public sealed interface PositionsValue permits PositionsValue.Known, PositionsValue.Unknown {

    record Known(long lamports) implements PositionsValue {
        public Known {
            if (lamports < 0) {
                throw new IllegalArgumentException("a value is never negative");
            }
        }
    }

    record Unknown(RealCostUnknownReason reason) implements PositionsValue {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
