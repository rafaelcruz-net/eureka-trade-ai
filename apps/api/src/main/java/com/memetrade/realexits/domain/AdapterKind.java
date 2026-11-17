package com.memetrade.realexits.domain;

/**
 * Who holds the conditional (BR9.1): the simulated adapter of the LOCALNET and the tests (no transaction, the token
 * stays in the bot wallet) or the Jupiter Trigger API (after the recording of BR0, the token in the custody account).
 */
public enum AdapterKind {
    SIMULATED,
    JUPITER
}
