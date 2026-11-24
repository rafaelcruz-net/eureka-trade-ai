package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;

/**
 * 261121-paper-real-unico U3 (NFR3.9, NFR3.11): where {@link ModePnlView} counts a block or a side that became
 * UNAVAILABLE. Each route passes its own series: C4 {@code PnlModeMetrics::unavailable}, C6
 * {@code PnlCompareMetrics::sideUnavailable}, C5 its own log. Counting an event is not arithmetic of a value.
 */
@FunctionalInterface
public interface UnavailableSink {

    void unavailable(PnlMarket market, PnlModeMetrics.Cause cause);
}
