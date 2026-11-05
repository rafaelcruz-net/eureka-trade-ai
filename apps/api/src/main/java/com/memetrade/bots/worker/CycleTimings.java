package com.memetrade.bots.worker;

import java.time.Duration;

/**
 * The stopwatch of the cycle stages (performance-requirements.md "Frescor dentro da fatia"): only the {@code loadtest}
 * profile provides one ({@code bots.loadtest.CycleStopwatch}); everywhere else it is {@link #NONE} and costs nothing.
 * There is no permanent histogram in production (NFR2.1 to NFR2.3 are measured by the load scenario {@code u5}).
 */
public interface CycleTimings {

    CycleTimings NONE = new CycleTimings() {};

    /** The whole cycle, from the start of the transaction to the commit. */
    default void cycle(Duration duration, CycleResult result) {}

    /** The reads of the inputs, the decision and its record. */
    default void decision(Duration duration) {}

    /** {@code OrderGate.evaluate} plus {@code PaperExecution.execute} (NFR2.3). */
    default void barrierAndExecution(Duration duration) {}
}
