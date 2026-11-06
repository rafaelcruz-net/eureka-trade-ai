package com.memetrade.audit.api;

/**
 * U8 addition to the public interface of the AuditTrail (C9 family, system status of BR3.1): how many audit events
 * still wait in the outbox for the relay. Only the number; no event, actor or detail leaves through it.
 */
public interface AuditBacklog {

    /** Outbox rows not yet delivered to the trail. */
    long pending();
}
