package com.memetrade.bots.worker;

import com.memetrade.pnl.api.HoldingsResult;
import java.util.Objects;

/**
 * 261121-paper-real-unico U4 (F1; BR2.5, BR3.1): what the step before the transaction of the cycle left for it.
 *
 * <ul>
 *   <li>{@link Skipped}: no read (a PAPER bot, the flag off, or a check before the lock said the bot will not run);
 *   <li>{@link Read}: the mark M0 of the bot, read before the snapshot, and the answer of the read of C15;
 *   <li>{@link NotRead}: under the lock the REAL bot does run although nothing was read before it (the race of BR2.5):
 *       no sale, READ_FAILED, and no metric of a read that never happened.
 * </ul>
 */
public sealed interface HoldingsSnapshot
        permits HoldingsSnapshot.Skipped, HoldingsSnapshot.Read, HoldingsSnapshot.NotRead {

    /** Nothing was read; {@code precheck} says which check stopped it (for the log of the race). */
    record Skipped(String precheck) implements HoldingsSnapshot {

        public Skipped {
            Objects.requireNonNull(precheck, "precheck");
        }
    }

    /** The mark M0 and the result of the read. */
    record Read(long m0, HoldingsResult result) implements HoldingsSnapshot {

        public Read {
            Objects.requireNonNull(result, "result");
        }
    }

    /** The bot runs under the lock without a read. */
    record NotRead() implements HoldingsSnapshot {}
}
