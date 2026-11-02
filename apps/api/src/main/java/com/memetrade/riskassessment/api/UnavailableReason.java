package com.memetrade.riskassessment.api;

/** Why the indicator is unavailable (BR3.3, BR3.8); the assessment is then NO_TRADE. */
public enum UnavailableReason {
    INSUFFICIENT_COVERAGE,
    SOURCE_UNAVAILABLE
}
