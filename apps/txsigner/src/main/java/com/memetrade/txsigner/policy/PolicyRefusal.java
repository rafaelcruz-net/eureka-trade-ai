package com.memetrade.txsigner.policy;

/**
 * Reason code of a refused signature (BR5.1, BR5.2). The api receives only {@code SIGNING_POLICY_REFUSED} with this
 * code; the reason goes to the SigningRecord, the audit event and the metric {@code txsigner_decisions_total}.
 */
public enum PolicyRefusal {
    MALFORMED_TRANSACTION,
    CONTEXT_MISSING,
    ALT_NOT_ALLOWED,
    EXTRA_SIGNER,
    FEE_PAYER_MISMATCH,
    DURABLE_NONCE_NOT_ALLOWED,
    PROGRAM_NOT_ALLOWED,
    INSTRUCTION_NOT_ALLOWED,
    ACCOUNT_NOT_ALLOWED,
    DESTINATION_NOT_ALLOWED,
    AMOUNT_MISMATCH,
    /** U4 review (Minor 4): the route spends another account than the bot wallet's of the order's input mint. */
    SOURCE_NOT_ALLOWED,
    FEE_MISMATCH,
    FEE_TOO_HIGH,
    OPERATION_NOT_SUPPORTED,
    REAL_DISABLED,
    WALLET_NOT_FOUND,
    WALLET_BLOCKED,
    RECOVERY_IN_PROGRESS,
    REQUEST_MISMATCH,
    // 261113-trading-real-bot U4 (BR4.3, U4-NFR1.5): the reasons of the swap policy.
    LOOKUP_TABLE_NOT_ALLOWED,
    SIGNER_NOT_ALLOWED,
    MAIN_SIGNATURE_INVALID,
    MAIN_WALLET_NOT_ALLOWED,
    PRIORITY_FEE_TOO_HIGH,
    RENT_PAYER_NOT_ALLOWED,
    FUNDS_OWNER_NOT_ALLOWED,
    MIN_OUT_TOO_LOW,
    SLIPPAGE_TOO_HIGH,
    AUTHORITY_CHANGE,
    CLOSE_NOT_ALLOWED,
    CAP_EXCEEDED,
    MODE_NOT_ALLOWED,
    /**
     * 261113-trading-real-bot U7 (BR0.3, U7-NFR1.2): {@code TRIGGER_CREATE} and {@code TRIGGER_CANCEL} until the
     * {@code TriggerPolicy} exists, written only after the real recording of the Jupiter Trigger API.
     */
    POLICY_NOT_RECORDED;

    /** U7 (U7-NFR5.4): the closed label set of {@code txsigner_trigger_refusals_total}; grows with the TriggerPolicy. */
    public static final java.util.Set<PolicyRefusal> TRIGGER_REASONS = java.util.EnumSet.of(POLICY_NOT_RECORDED);

    /** The reasons a SWAP can be refused with: the closed label set of {@code txsigner_swap_decisions_total}. */
    public static final java.util.Set<PolicyRefusal> SWAP_REASONS = java.util.EnumSet.of(
            MALFORMED_TRANSACTION,
            CONTEXT_MISSING,
            LOOKUP_TABLE_NOT_ALLOWED,
            SIGNER_NOT_ALLOWED,
            MAIN_SIGNATURE_INVALID,
            MAIN_WALLET_NOT_ALLOWED,
            DURABLE_NONCE_NOT_ALLOWED,
            PROGRAM_NOT_ALLOWED,
            INSTRUCTION_NOT_ALLOWED,
            DESTINATION_NOT_ALLOWED,
            AMOUNT_MISMATCH,
            SOURCE_NOT_ALLOWED,
            FEE_MISMATCH,
            PRIORITY_FEE_TOO_HIGH,
            RENT_PAYER_NOT_ALLOWED,
            FUNDS_OWNER_NOT_ALLOWED,
            MIN_OUT_TOO_LOW,
            SLIPPAGE_TOO_HIGH,
            AUTHORITY_CHANGE,
            CLOSE_NOT_ALLOWED,
            CAP_EXCEEDED,
            MODE_NOT_ALLOWED,
            REAL_DISABLED,
            WALLET_NOT_FOUND,
            WALLET_BLOCKED,
            REQUEST_MISMATCH);
}
