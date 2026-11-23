package com.memetrade.pnl.api;

import com.memetrade.ledger.api.RealCostUnknownReason;
import java.util.Map;
import java.util.Objects;

/**
 * 261121-paper-real-unico U2 (decision F-3, C11, BR2.4, BR2.7): the cost of the open positions of one bot wallet, read
 * with attribution: {@link Known} lamports per mint (the sum of the holders, at least 0 each), or {@link Unknown} with
 * the reason. Never 0 and never {@code null} for an unknown cost.
 */
public sealed interface WalletCost permits WalletCost.Known, WalletCost.Unknown {

    /** The cost per mint of every open position of the wallet. */
    record Known(Map<String, Long> costByMint) implements WalletCost {
        public Known {
            costByMint = Map.copyOf(costByMint);
            if (costByMint.values().stream().anyMatch(cost -> cost < 0)) {
                throw new IllegalArgumentException("a cost is never negative");
            }
        }
    }

    /** The read with attribution gave no cost. */
    record Unknown(RealCostUnknownReason reason) implements WalletCost {
        public Unknown {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
