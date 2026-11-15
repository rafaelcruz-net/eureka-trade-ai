package com.memetrade.marketdata.api;

/**
 * U2 (BR1.5, entities.md SourceKind): whether the active market source is real or simulated. Derived from
 * {@link SourceRef#simulated()}: SIMULATED as soon as one configured source is simulated. U3 refuses a REAL order with
 * SIMULATED.
 */
public enum SourceKind {
    REAL,
    SIMULATED
}
