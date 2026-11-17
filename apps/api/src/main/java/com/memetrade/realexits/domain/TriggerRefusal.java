package com.memetrade.realexits.domain;

/** BR3 and BR2.6: why the prices of a conditional are refused; the same names are the codes of the API. */
public enum TriggerRefusal {
    VALIDATION_FAILED,
    PRICE_UNKNOWN,
    TP_NOT_ABOVE_SL,
    TRIGGER_ON_WRONG_SIDE,
    TRIGGER_OUT_OF_BOUNDS
}
