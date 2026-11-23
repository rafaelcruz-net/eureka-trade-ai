package com.memetrade.pnl.api;

/**
 * 261121-paper-real-unico U2 (BR2.7, NFR1.4, NFR1.12, NFR1.23): who reads the cost of a bot wallet with attribution, and
 * so its budget: {@code GATE} (the rounds of the barrier, in the worker) and {@code CYCLE} (the REAL cycle, in the
 * worker) have 250 ms; {@code PNL} (the REAL block of the P&L, in the api) has 500 ms. The {@code path} label of
 * {@code pnl_real_attribution_read_seconds}.
 */
public enum CostPath {
    GATE,
    CYCLE,
    PNL
}
