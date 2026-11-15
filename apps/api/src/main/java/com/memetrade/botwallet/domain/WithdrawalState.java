package com.memetrade.botwallet.domain;

/** States of a withdrawal (BR4.7, functional-spec.md state machine). */
public enum WithdrawalState {
    REQUESTED,
    SIGNED,
    SENT,
    CONFIRMED,
    FAILED,
    AMBIGUOUS;

    public boolean isFinal() {
        return this == CONFIRMED || this == FAILED;
    }
}
