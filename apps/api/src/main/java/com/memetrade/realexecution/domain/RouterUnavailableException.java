package com.memetrade.realexecution.domain;

/** X1: the aggregator did not answer, answered 5xx or its circuit is open (BR9.1); never a refusal of a route. */
public class RouterUnavailableException extends RuntimeException {

    public RouterUnavailableException(String message) {
        super(message);
    }
}
