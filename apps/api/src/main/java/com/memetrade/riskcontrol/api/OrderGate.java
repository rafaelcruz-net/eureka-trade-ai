package com.memetrade.riskcontrol.api;

/**
 * C14, U6 for U5: the mandatory barrier before every simulated order (ADR-003). U5 calls it inside the cycle
 * transaction, with the principal of the bot's owner bound in the authentication context (C10), for every BUY or SELL
 * decision; HOLD and NO_TRADE never reach it.
 *
 * <p>Rules (contract-summary.md, functional-spec.md F1 to F4):
 *
 * <ul>
 *   <li>Requires an open transaction (propagation {@code MANDATORY}); without one it fails and writes nothing (BR1.1).
 *   <li>Tenant and person come only from the authentication context, never from the order or the limits.
 *   <li>Reads price and liquidity (C11) and the risk view (C13) once per call (BR1.3); keeps nothing in memory.
 *   <li>{@link GateResult.Approved} is the only way to the execution; U5 passes the same limits version to
 *       {@code PaperExecution.execute}. {@link GateResult.Rejected} is recorded with its details and one
 *       {@code ORDER_REJECTED} audit event in the same transaction; U5 does not execute and applies every pause
 *       trigger of {@code pauseTriggers} in the same transaction.
 *   <li>The second evaluation of the same decision is {@code Rejected([DUPLICATE])} (BR2.8).
 *   <li>Every technical failure (C11, C13, database, timeout) throws and rolls the whole cycle back, without retry and
 *       never as an approval (NFR10.2). Everything is PAPER; nothing is signed or sent.
 * </ul>
 */
public interface OrderGate {

    GateResult evaluate(CandidateOrder order, BotLimitsVersionRef limits);
}
