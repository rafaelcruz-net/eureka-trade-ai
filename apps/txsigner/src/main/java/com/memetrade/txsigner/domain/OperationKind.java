package com.memetrade.txsigner.domain;

/**
 * Kind of transaction the BotWallet asks to sign (contract R1). {@code SWAP} came with the U4 of 261113-trading-real-bot
 * (contract addendum: one kind with the mode and the side in its context); {@code SWAP_BUY} and {@code SWAP_SELL} of the
 * first contract stay refused as {@code OPERATION_NOT_SUPPORTED}.
 */
public enum OperationKind {
    WITHDRAWAL,
    SWAP_BUY,
    SWAP_SELL,
    SWAP
}
