package com.memetrade.botwallet.domain;

/** Operation mode of the REAL, from the most to the least restrictive (FR4.1). */
public enum OperationMode {
    SIGNED,
    AUTO_EXITS,
    AUTO_FULL;

    public boolean isMorePermissiveThan(OperationMode other) {
        return ordinal() > other.ordinal();
    }
}
