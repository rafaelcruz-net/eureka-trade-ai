/**
 * Public interface of the BotWallet module (U1 of 261113-trading-real-bot, components.md): the bot wallet of a person,
 * its REAL preferences, the deposits and the withdrawals (M1). No other module reads its tables
 * ({@code bot_wallet_account}, {@code real_preferences}, {@code consent_challenge}, {@code wallet_consent},
 * {@code used_nonce}, {@code real_release}, {@code observed_deposit}, {@code withdrawal},
 * {@code reconciliation_mark}). The module never signs: only the TxSigner process holds the KMS credential (ADR-010).
 *
 * <p>Since the U3 (BR7.3, Q11): {@link com.memetrade.botwallet.api.RealStateQuery} ({@code readRealState} with the set
 * of pauses and the start of each one, and the wallets the jobs of the barrier go through) and
 * {@link com.memetrade.botwallet.api.RealPauses} ({@code pauseUser} and {@code resumeUser} of one reason).
 */
package com.memetrade.botwallet.api;
