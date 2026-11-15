package com.memetrade.marketdata.infra.coingecko;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * The monthly call counter of a real source, table {@code marketdata_provider_usage} (U2 reliability-design.md). Every
 * method runs in a short transaction of its own; a database failure surfaces as
 * {@link org.springframework.dao.DataAccessException}, which puts {@link CallBudget} in conservative mode.
 */
public interface UsageStore {

    /**
     * Adds one call to ({@code source}, {@code month}) only while {@code calls x 100 < stopPercent x budget}.
     *
     * @return the new count, or empty when the priority threshold was already reached (nothing added)
     */
    OptionalLong increment(String source, String month, int stopPercent, long budget, Instant now);

    /** Keeps the greatest value between the stored count and {@code calls} (NFR3.7); never lowers it. */
    long raiseTo(String source, String month, long calls, Instant now);

    /** The stored count of the month, 0 without a row. */
    long calls(String source, String month);
}
