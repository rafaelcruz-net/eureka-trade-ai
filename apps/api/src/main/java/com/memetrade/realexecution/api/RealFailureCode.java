package com.memetrade.realexecution.api;

/**
 * Why a REAL order ended FAILED or CANCELLED (entities.md RealOrder.failureCode). The same names label the metric
 * {@code real_orders_total{code}}, with {@code NONE} and {@code REQUOTED} added there (closed labels, U4-NFR5.1).
 */
public enum RealFailureCode {
    QUOTE_EXPIRED,
    UPSTREAM_UNAVAILABLE,
    SIGNING_REFUSED,
    SLIPPAGE_EXCEEDED,
    BLOCKHASH_EXPIRED,
    CHAIN_REJECTED,
    SIGNING_DEADLINE_EXPIRED,
    USER_REJECTED,
    MODE_LOWERED,
    REAL_DISABLED,
    PAUSED,
    KILL_SWITCH,
    RISK_REJECTED,
    ROUTE_TOO_LARGE,
    LIQUIDATION,
    /**
     * The Jupiter refused the route of this order (a 4xx other than 429: no route, a mint it does not trade). A refusal
     * of the order, not an outage: it counts neither in the circuit nor toward the UPSTREAM pause (review, Major 1).
     */
    NO_ROUTE
}
