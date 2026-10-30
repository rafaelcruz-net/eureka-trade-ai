package com.memetrade.audit.api;

/** Who performed an audited action (C9); stored in {@code audit_event.actor_type} with the same upper-case name. */
public enum ActorType {
    /** An authenticated person acting inside their tenant; the actor is the user id. */
    PERSON,
    /** An administrator acting across tenants (for example {@code AUDIT_QUERIED}); no tenant on the event. */
    ADMIN,
    /** The system itself, such as a scheduled job ({@code AUDIT_RETENTION_PURGED}); actor {@code system}. */
    SYSTEM,
    /** Someone before login; the actor is the masked wallet address and the event has no tenant. */
    ANONYMOUS
}
