package com.memetrade.riskassessment.api;

/** Hint for the decision of U5 (FR6.2): NO_TRADE whenever the indicator is unavailable. Never authorises trading. */
public enum DecisionHint {
    NO_TRADE,
    ELIGIBLE
}
