package com.memetrade.txsigner.domain;

/**
 * Answer of a wallet creation: only the public address. The key reference never leaves the TxSigner (NFR1.7), so the
 * answer cannot leak it.
 */
public record CreatedWallet(String botWalletAddress) {}
