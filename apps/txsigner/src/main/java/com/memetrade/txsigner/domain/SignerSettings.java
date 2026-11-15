package com.memetrade.txsigner.domain;

/**
 * Fixed settings of the TxSigner domain: the REAL flag (off by default, NFR1.11), the network of this TxSigner, the
 * absolute cap of a consent (10 SOL, BR2.1), the fee ceiling of a withdrawal (BR5.1 f), the compute unit ceiling of a
 * withdrawal (BR5.1 d), the base fee per signature, the creation limit per hour (security-design.md) and, since the U4
 * of 261113-trading-real-bot, the ceiling of the priority fee of a swap (BR4.3, 0.001 SOL by default).
 */
public record SignerSettings(
        boolean realEnabled,
        String network,
        long maxCapLamports,
        long maxWithdrawalFeeLamports,
        int maxWithdrawalComputeUnits,
        long baseFeeLamportsPerSignature,
        int maxKeysPerHour,
        long maxSwapPriorityFeeLamports) {

    /** BR4.3: the default ceiling of the priority fee of a swap. */
    public static final long DEFAULT_MAX_SWAP_PRIORITY_FEE_LAMPORTS = 1_000_000L;

    /** The settings of the U1, with the default ceiling of the priority fee of a swap. */
    public SignerSettings(
            boolean realEnabled,
            String network,
            long maxCapLamports,
            long maxWithdrawalFeeLamports,
            int maxWithdrawalComputeUnits,
            long baseFeeLamportsPerSignature,
            int maxKeysPerHour) {
        this(
                realEnabled,
                network,
                maxCapLamports,
                maxWithdrawalFeeLamports,
                maxWithdrawalComputeUnits,
                baseFeeLamportsPerSignature,
                maxKeysPerHour,
                DEFAULT_MAX_SWAP_PRIORITY_FEE_LAMPORTS);
    }
}
