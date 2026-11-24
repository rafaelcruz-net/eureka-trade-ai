package com.memetrade.pnl.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 261121-paper-real-unico U4 (C15, ENT-403): the answer of the read of the own holdings of a bot: available, with one
 * holding per mint and the instant of the read, or unavailable, with its reason. Never an exception for the cycle and
 * never the position of the whole wallet (BR2.3).
 */
public sealed interface HoldingsResult permits HoldingsResult.Available, HoldingsResult.Unavailable {

    /** The holdings of the snapshot, one per mint. */
    record Available(List<BotHolding> holdings, Instant readAt) implements HoldingsResult {

        public Available {
            holdings = List.copyOf(holdings);
            Objects.requireNonNull(readAt, "readAt");
        }
    }

    /** The read did not give the holdings; the cycle sells nothing by itself in this slot. */
    record Unavailable(UnavailableReason reason) implements HoldingsResult {

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
