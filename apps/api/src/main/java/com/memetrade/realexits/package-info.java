/**
 * 261113-trading-real-bot U7 {@code u7-exits-and-fiscal}: the conditional exits (stop, take-profit and OCO of the
 * Jupiter Trigger API) of the REAL positions of the bot wallet, and the fiscal CSV export of the REAL ledger.
 *
 * <p>The module talks to the others only through their public interfaces ({@code botwallet.api}, {@code ledger.api},
 * {@code riskcontrol.api}, {@code realexecution.api}, {@code marketdata.api}, {@code identity.api}, {@code audit.api})
 * and implements the extension interfaces they declare ({@code PositionExitGuard} and {@code RealExitChanges} of M6;
 * {@code EscrowBalances}, {@code OpenExitsCheck} and {@code ExitsAvailability} of M1), so no module imports it
 * (logical-components.md, Q2). It never signs: the bot wallet signs only in the TxSigner. Until the real recording of
 * the Trigger API (BR0) the conditionals run only with the simulated adapter of the local test profile, which sends
 * no transaction.
 */
package com.memetrade.realexits;
