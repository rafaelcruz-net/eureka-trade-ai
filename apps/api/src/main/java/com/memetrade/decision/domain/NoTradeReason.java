package com.memetrade.decision.domain;

/**
 * Why a decision is NO_TRADE, or why a token gave no signal (C4 {@code noTradeReason} with the U5 additions
 * {@link #RISK_BAND_HIGH}, {@link #NO_SIGNAL}, {@link #NO_TOKENS} and {@link #QUANTITY_BELOW_MINIMUM}). A missing
 * market value always becomes one of these reasons, never a zero (BR4.1, NFR4.6).
 */
public enum NoTradeReason {
    /** A bucket or value the strategy needs is UNKNOWN (or a base value is zero). */
    UNKNOWN_DATA,
    /** The last bucket or the reference price is older than 5 minutes. */
    STALE_DATA,
    /** The window does not reach back as far as the strategy needs. */
    INSUFFICIENT_HISTORY,
    /** The risk assessment of C12 is unavailable. */
    ASSESSMENT_UNAVAILABLE,
    /** A SELL without a position is never emitted (BR4.10). */
    NO_POSITION_TO_SELL,
    /** The buy signal exists but the risk band is HIGH (BR4.7). */
    RISK_BAND_HIGH,
    /** Known data, no signal (BR4.8). */
    NO_SIGNAL,
    /** The selection yielded no token (BR4.8). */
    NO_TOKENS,
    /** The buy quantity truncates to zero (BR4.6). */
    QUANTITY_BELOW_MINIMUM
}
