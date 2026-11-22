package com.memetrade.riskcontrol.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 261121-paper-real-unico U1, C9 (ENT-003 to ENT-005, BR3.1, BR3.2): the four REAL limits of the account, in exact
 * integers (lamports) and exact decimals (percentages); never floating point.
 *
 * @param cap the cap of the ACTIVE bot wallet; NOT_DEFINED when it is zero
 * @param orderMax {@code floor(cap x orderMaxBps / 10000)} lamports and {@code orderMaxBps / 100} percent of the cap;
 *     NOT_DEFINED without a cap
 * @param dailyBuys approved buys of one bot per UTC day; always known, the configuration always has it
 * @param dailyLoss {@code floor(base x dailyLossMaxBps / 10000)} lamports with {@code base = min(cap at the start of the
 *     UTC day, cap now)}, the same base as the barrier, and {@code dailyLossMaxBps / 100} percent; NOT_DEFINED without a
 *     cap
 */
public record RealAccountLimitsView(Amount cap, Amount orderMax, int dailyBuys, Amount dailyLoss) {

    public RealAccountLimitsView {
        Objects.requireNonNull(cap, "cap");
        Objects.requireNonNull(orderMax, "orderMax");
        Objects.requireNonNull(dailyLoss, "dailyLoss");
        if (dailyBuys < 0) {
            throw new IllegalArgumentException("dailyBuys is never negative");
        }
    }

    /** A limit in lamports, with its share of the cap when it is one; or not defined (never a zero for "unknown"). */
    public sealed interface Amount permits Amount.Known, Amount.NotDefined {

        /** @param percentOfCap {@code null} for the cap itself; the exact share otherwise */
        record Known(long lamports, BigDecimal percentOfCap) implements Amount {
            public Known {
                if (lamports < 0) {
                    throw new IllegalArgumentException("A limit is never negative");
                }
            }
        }

        record NotDefined() implements Amount {}
    }
}
