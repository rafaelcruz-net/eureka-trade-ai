package com.memetrade.realexecution.domain;

/**
 * X1 (review of the U4, Major 1): the aggregator answered, but refused the route of this order (a 4xx other than 429:
 * no route, a mint it does not trade, an amount it will not quote). A refusal of the order, never an outage: it counts
 * neither in the circuit nor toward the UPSTREAM pause, and the order fails {@code NO_ROUTE}.
 */
public class NoRouteException extends RuntimeException {

    public NoRouteException(String message) {
        super(message);
    }
}
