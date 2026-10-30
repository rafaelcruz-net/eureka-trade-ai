package com.memetrade.audit.api;

/**
 * Final audit publishing interface (contract C9), the same from U1 onwards.
 *
 * <p>{@link #publish(AuditEventCommand)} requires an active transaction (propagation {@code MANDATORY}): the event
 * is written to {@code audit_outbox} together with the audited action, so a rolled-back action leaves no event and a
 * committed one always produces exactly one event in the trail. The AuditTrail relay, running only in the API
 * process, later copies each outbox row into the append-only {@code audit_event} table by {@code eventId}.
 *
 * <p>Rules for callers: publish at most one event per sensitive action; an idempotent retry reuses the same
 * {@code eventId}; never put a secret, token, nonce, signature or the full wallet address outside {@code details}.
 * No module reads {@code audit_outbox} directly.
 */
public interface AuditEventPublisher {

    /**
     * Writes the event to the outbox in the caller's transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is active
     * @throws AuditPublishRejectedException when an action does not follow the upper-case format, a PERSON event has
     *     no tenant, a field is too long, or {@code details} / {@code targetRef} carry a forbidden key or a value
     *     shaped like a JWT, a Bearer credential or a Base58 64-byte signature (BR1.2, BR1.4); the caller's
     *     transaction then rolls back with the action
     */
    void publish(AuditEventCommand event);
}
