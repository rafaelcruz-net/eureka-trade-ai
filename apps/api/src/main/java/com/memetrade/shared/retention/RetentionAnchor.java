package com.memetrade.shared.retention;

/** From when a retention period counts (entities.md RetentionPeriod.anchor, J7). */
public enum RetentionAnchor {
    /** From the creation (or occurrence) of the row. */
    CREATED,
    /** From the delivery of an outbox row. */
    DELIVERED,
    /** From the expiration of the row (a login challenge). */
    EXPIRED,
    /** From the end of the row (a session: revocation or the earlier expiration). */
    ENDED,
    /** From the conclusion of the row (a data subject request). */
    CONCLUDED
}
