package com.memetrade.txsigner.policy;

/** Operation mode authorized by a consent, from the most to the least restrictive. */
public enum OperationMode {
    SIGNED,
    AUTO_EXITS,
    AUTO_FULL;

    /** {@code true} when this mode allows less than {@code other} (a lowering only restricts, BR2.2). */
    public boolean isMoreRestrictiveThan(OperationMode other) {
        return ordinal() < other.ordinal();
    }
}
