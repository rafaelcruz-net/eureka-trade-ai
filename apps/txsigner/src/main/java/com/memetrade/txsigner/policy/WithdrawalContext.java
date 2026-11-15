package com.memetrade.txsigner.policy;

/**
 * Context of a WITHDRAWAL sent by the BotWallet (contract R1, addendum of the U1): the amount of the withdrawal and the
 * fee calculated before signing and recorded on it (BR4.4, NFR4.3). The context can only restrict; a missing field
 * refuses the signature (BR5.2).
 */
public record WithdrawalContext(Long lamports, Long feeLamports) {}
