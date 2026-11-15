package com.memetrade.ledger.api;

import java.util.List;
import java.util.Objects;

/**
 * M2 {@code view} (261113-trading-real-bot U3, review R-01): the balance, the open positions and the flows after an
 * instant of one bot wallet, read in ONE SQL statement, so they come from one snapshot of PostgreSQL. A fill or a
 * deposit committed while it is read is in all three parts or in none: it is never counted twice nor half.
 *
 * @param flows the deposits and withdrawals after the instant asked for; {@link RealFlows#NONE} when none was asked
 */
public record RealLedgerView(RealBalance balance, List<RealPosition> positions, RealFlows flows) {

    public RealLedgerView {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(flows, "flows");
        positions = List.copyOf(positions);
    }
}
