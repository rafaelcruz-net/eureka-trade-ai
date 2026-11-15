package com.memetrade.botwallet.domain;

/** No RPC provider answered (both circuits open, both out of budget or both failing): the worker waits. */
public class ChainUnavailableException extends RuntimeException {

    public ChainUnavailableException(String message) {
        super(message);
    }
}
