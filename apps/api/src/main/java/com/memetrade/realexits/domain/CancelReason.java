package com.memetrade.realexits.domain;

/** entities.md {@code cancel_reason} (BR6, BR4.3): what started the cancellation; also the label of the metric. */
public enum CancelReason {
    KILL_SWITCH,
    MODE_CHANGE,
    RELEASE_REVOKED,
    MARKET_SELL,
    USER
}
