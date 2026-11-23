package com.memetrade.pnl.real;

import com.memetrade.pnl.api.RealModePnl;
import java.util.List;
import java.util.Objects;

/**
 * 261121-paper-real-unico U2 (F1, F3): the outcome of one pass over the REAL ledger of a person: the block of C10, the
 * reconciliation with the ledger, and the open positions by mint ({@code GET /api/v1/real/pnl}). The block is only
 * delivered when the reconciliation is MATCH (BR8.7); the caller decides.
 */
public record RealPnlResult(RealModePnl pnl, ReconciliationResult reconciliation, List<PositionValue> positions) {

    public RealPnlResult {
        Objects.requireNonNull(pnl, "pnl");
        Objects.requireNonNull(reconciliation, "reconciliation");
        positions = List.copyOf(positions);
    }
}
