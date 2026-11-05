package com.memetrade.riskcontrol.domain;

/**
 * Port of the error metric of the barrier (NFR9.2): every technical failure of an evaluation is counted with its cause
 * before the exception goes up. No person, tenant, bot, decision nor token ever becomes a label.
 */
public interface EvaluationErrors {

    /** The closed list of causes of {@code riskcontrol.evaluation.errors} (observability-design.md). */
    enum Cause {
        MARKET_DATA,
        PORTFOLIO,
        DATABASE,
        OTHER
    }

    void count(Cause cause);
}
