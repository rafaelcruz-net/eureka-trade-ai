package com.memetrade.botwallet.domain;

/**
 * Fee of a withdrawal known before signing (NFR4.3, BR5.1 f): base fee per signature plus
 * ceil(unit price x unit limit / 10^6); the TxSigner computes the same value and refuses another one.
 */
public final class WithdrawalFees {

    private static final long MICRO_LAMPORTS_PER_LAMPORT = 1_000_000L;

    private WithdrawalFees() {}

    public static long calculate(long baseFeePerSignature, int unitLimit, long microLamportsPerUnit) {
        if (microLamportsPerUnit == 0) {
            return baseFeePerSignature;
        }
        long micro = Math.multiplyExact(microLamportsPerUnit, unitLimit);
        long priority = Math.addExact(micro, MICRO_LAMPORTS_PER_LAMPORT - 1) / MICRO_LAMPORTS_PER_LAMPORT;
        return Math.addExact(baseFeePerSignature, priority);
    }
}
