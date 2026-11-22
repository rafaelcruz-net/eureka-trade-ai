package com.memetrade.pnl.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 261121-paper-real-unico U1, C10 (BR5.5): the REAL total of the mode, in lamports. In the U1 it is the calculation of
 * today over every settled order of the person (the same number as {@code GET /api/v1/real/pnl}), net of fees, with
 * {@code total = realized + unrealized}; unknown when the unrealized is unknown, the realized and fees staying exact.
 */
public record RealModeTotal(
        long realizedLamports,
        LamportsValue unrealized,
        long feesLamports,
        LamportsValue total,
        Instant unrealizedAsOf) {

    public static final String CURRENCY = "SOL";

    public RealModeTotal {
        Objects.requireNonNull(unrealized, "unrealized");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(unrealizedAsOf, "unrealizedAsOf");
    }

    public String currency() {
        return CURRENCY;
    }
}
