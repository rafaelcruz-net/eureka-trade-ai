package com.memetrade.txsigner.policy;

/** A BUY spends lamports of the bot wallet for a token; a SELL sells a token of the bot wallet back to SOL. */
public enum SwapSide {
    BUY,
    SELL
}
