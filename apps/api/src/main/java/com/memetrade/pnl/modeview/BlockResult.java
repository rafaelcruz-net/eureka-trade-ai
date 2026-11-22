package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import java.util.Objects;

/**
 * 261121-paper-real-unico U1 (ENT-013, BR5.3): one block of the P&L by mode: the block of its market, or UNAVAILABLE
 * with a machine code and the correlation id of the request, never a monetary value.
 */
public sealed interface BlockResult<T> permits BlockResult.Available, BlockResult.Unavailable {

    record Available<T>(T block) implements BlockResult<T> {
        public Available {
            Objects.requireNonNull(block, "block");
        }
    }

    /** @param currency USD with PAPER, SOL with REAL */
    record Unavailable<T>(PnlMarket market, String currency, String code, String correlationId)
            implements BlockResult<T> {
        public Unavailable {
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }
}
