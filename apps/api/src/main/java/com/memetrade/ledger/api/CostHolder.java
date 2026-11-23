package com.memetrade.ledger.api;

/**
 * 261121-paper-real-unico U2 (C11, ENT-205, BR2.8 item 2): the holder of a cost position inside a bot wallet, as the
 * cost path of {@link RealCostMethod} sees it: a REAL bot of the person or the part without owner. Implemented by the
 * P&L ({@code pnl.real.Holder}), so the ledger never depends on the P&L. Its natural order is the tie-break of the
 * largest remainder (BR4.2): bots by creation, then id; the part without owner always last.
 */
public interface CostHolder extends Comparable<CostHolder> {

    /** True for the part without owner (UNOWNED). */
    boolean unowned();
}
