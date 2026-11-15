package com.memetrade.riskcontrol.api;

/**
 * 261113-trading-real-bot U3 (contract M5, entities.md GateDecision): the code of a refused REAL order, one per rule
 * group, in the fixed order of BR1.3. The same names travel in Java, in the database and in the metric label.
 */
public enum RealRejectionCode {
    REAL_PAUSED,
    SIMULATED_SOURCE_ACTIVE,
    TOKEN_NOT_ELIGIBLE,
    STALE_DATA,
    REAL_LIMIT_EXCEEDED,
    REAL_CAP_EXCEEDED
}
