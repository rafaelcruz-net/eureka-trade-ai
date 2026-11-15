package com.memetrade.txsigner.policy;

/**
 * Who signs a swap (contract R1 addendum of the U4): the bot wallet alone ({@code AUTO_FULL}), or the registered main
 * wallet first, as fee payer, and then the bot wallet as owner of the funds ({@code SIGNED}, also used by the market
 * orders of the mode AUTO_EXITS).
 */
public enum SwapMode {
    SIGNED,
    AUTO_FULL
}
