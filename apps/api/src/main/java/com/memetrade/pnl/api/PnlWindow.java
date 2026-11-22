package com.memetrade.pnl.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 261121-paper-real-unico U1, C10 (BR5.6): the period of the P&L by mode. The U1 publishes only {@link All}; the U3
 * adds {@link Range} to the request ({@code [from, to)} in UTC, at most 366 days).
 */
public sealed interface PnlWindow permits PnlWindow.All, PnlWindow.Range {

    static PnlWindow all() {
        return All.INSTANCE;
    }

    /** Since the beginning. */
    record All() implements PnlWindow {
        static final All INSTANCE = new All();
    }

    record Range(Instant from, Instant to) implements PnlWindow {
        public static final Duration MAX = Duration.ofDays(366);

        public Range {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX) > 0) {
                throw new IllegalArgumentException("A range is [from, to) with from before to and at most 366 days");
            }
        }
    }
}
