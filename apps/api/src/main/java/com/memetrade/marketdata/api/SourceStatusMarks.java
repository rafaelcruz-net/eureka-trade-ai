package com.memetrade.marketdata.api;

/**
 * Contract C18, owned by U4 and read by the live channel of U5: a global mark that only grows and changes, in the same
 * transaction, every time the state of a source changes (BR1.11). Equal for everybody (no customer data).
 */
public interface SourceStatusMarks {

    long globalMark();
}
