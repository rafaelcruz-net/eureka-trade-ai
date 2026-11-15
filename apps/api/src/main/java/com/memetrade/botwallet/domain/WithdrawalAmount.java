package com.memetrade.botwallet.domain;

/** Amount of a withdrawal and its fee known before signing (NFR4.3); the reserve holds both. */
public record WithdrawalAmount(WithdrawalKind kind, long lamports, long feeLamports) {

    public long reservedLamports() {
        return Math.addExact(lamports, feeLamports);
    }
}
