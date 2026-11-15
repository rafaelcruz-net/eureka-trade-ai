package com.memetrade.txsigner.codec;

/** The three header bytes of a Solana message. */
public record MessageHeader(int requiredSignatures, int readonlySigned, int readonlyUnsigned) {}
