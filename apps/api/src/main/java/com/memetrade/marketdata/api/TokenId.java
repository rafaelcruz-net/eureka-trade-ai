package com.memetrade.marketdata.api;

/** Tokens are identified by chain + mint; the symbol is never an identity (BR3.2). */
public record TokenId(String chain, String mint) {}
