package com.memetrade.realexecution.infra;

/**
 * The points right after each recorded step of an order (U4-NFR4.3): {@code quoted}, {@code signature-received}
 * (the TxSigner answered, nothing recorded yet), {@code signed}, {@code sent-recorded} (SENT and the attempt committed,
 * nothing sent yet) and {@code send-accepted} (the network accepted, the outcome not recorded yet). In production
 * nothing happens there; the tests stop the worker at one of them and resume from the database.
 */
@FunctionalInterface
public interface CrashPoints {

    CrashPoints NONE = point -> {};

    void reached(String point);
}
