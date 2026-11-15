package com.memetrade.marketdata.api;

/**
 * U2 (BR1.7): what a consumer derives from a {@link ResolvedField} without a new field. With one real source, a value
 * is "single source": exactly one fresh origin and no divergence. The risk evaluation reads it as reduced coverage; with
 * two real sources the existing divergence check goes on.
 */
public final class ResolvedFields {

    private ResolvedFields() {}

    /** True when the shown KNOWN value rests on exactly one fresh origin, without divergence. */
    public static boolean singleSource(ResolvedField field) {
        return field.value().isKnown() && field.origins().size() == 1 && !field.divergent();
    }
}
