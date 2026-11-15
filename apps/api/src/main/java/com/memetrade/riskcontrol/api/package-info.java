/**
 * Public interface of RiskControl (U6): contract C14 ({@link com.memetrade.riskcontrol.api.OrderGate} and its types)
 * for the PAPER barrier and, since 261113-trading-real-bot U3, contract M5
 * ({@link com.memetrade.riskcontrol.api.RealRiskGate}, {@link com.memetrade.riskcontrol.api.OrderIntent},
 * {@link com.memetrade.riskcontrol.api.GateDecision} and the block list) for the REAL barrier. Other modules depend only
 * on this package; the rest of {@code com.memetrade.riskcontrol} is internal, and the REAL barrier lives apart in
 * {@code com.memetrade.riskcontrol.real} with tables of its own.
 */
package com.memetrade.riskcontrol.api;
