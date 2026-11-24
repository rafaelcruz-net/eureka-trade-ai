package com.memetrade.bots.domain;

import java.util.List;
import java.util.Objects;

/**
 * 261121-paper-real-unico U4 (ENT-403 as the cycle sees it): what the REAL cycle knows of the own holdings of the bot
 * when it decides: the positions read in the snapshot ({@link Known}), a read that failed ({@link Unavailable}), or no
 * read at all because the checks before the lock said the bot would not run and, under the lock, it does run
 * ({@link NotRead}, the race of BR2.5, treated as READ_FAILED).
 */
public sealed interface OwnHoldings permits OwnHoldings.Known, OwnHoldings.Unavailable, OwnHoldings.NotRead {

    /** The own positions of the snapshot, one per mint. */
    record Known(List<OwnPosition> positions) implements OwnHoldings {

        public Known {
            positions = List.copyOf(positions);
        }

        /** The available quantity of {@code mint}; 0 when the bot has no own position in it. */
        public long available(String mint) {
            return positions.stream()
                    .filter(position -> position.mint().equals(mint))
                    .mapToLong(OwnPosition::availableTokenBaseUnits)
                    .findFirst()
                    .orElse(0);
        }
    }

    /** The read failed for {@code reason}. */
    record Unavailable(OwnQuantityReason reason) implements OwnHoldings {

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The bot runs under the lock without a read before it. */
    record NotRead() implements OwnHoldings {}
}
