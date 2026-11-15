/**
 * Infrastructure of the BotWallet module: JDBC repositories, the TxSigner HTTP client, the ChainWatcher adapters and
 * the worker jobs. Only the worker talks to the Solana RPC; nothing here holds a KMS credential.
 */
package com.memetrade.botwallet.infra;
