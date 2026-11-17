package com.memetrade.txsigner.domain;

/**
 * Kind of transaction the BotWallet asks to sign (contract R1). {@code SWAP} came with the U4 of 261113-trading-real-bot
 * (contract addendum: one kind with the mode and the side in its context); {@code SWAP_BUY} and {@code SWAP_SELL} of the
 * first contract stay refused as {@code OPERATION_NOT_SUPPORTED}. {@code TRIGGER_CREATE} and {@code TRIGGER_CANCEL} came
 * with the U7.
 */
public enum OperationKind {
    WITHDRAWAL,
    SWAP_BUY,
    SWAP_SELL,
    SWAP,
    /**
     * 261113-trading-real-bot U7 (BR1.2, BR2): the creation of a conditional of the Jupiter Trigger, co-signed by the bot
     * wallet. Refused with {@code POLICY_NOT_RECORDED} until the real recording (BR0.3).
     */
    TRIGGER_CREATE,
    /** U7 (BR1.3, BR2.3): the cancellation of a conditional, signed only by the bot wallet; refused until the recording. */
    TRIGGER_CANCEL;

    /** U7: the operations of the conditional exits (the TriggerPolicy, after the recording of BR0). */
    public boolean isTrigger() {
        return this == TRIGGER_CREATE || this == TRIGGER_CANCEL;
    }
}
