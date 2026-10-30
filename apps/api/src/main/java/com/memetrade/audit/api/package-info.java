/**
 * Public interface of the AuditTrail module (contract C9), the only package other modules may use (ArchUnit rule
 * {@code AuditArchitectureTest}).
 *
 * <ul>
 *   <li>{@link com.memetrade.audit.api.AuditEventPublisher}: writes one event per sensitive action to the outbox in
 *       the caller's transaction; the relay delivers it to the append-only {@code audit_event} trail.
 *   <li>{@link com.memetrade.audit.api.AuditEventCommand}: the event, with factories for person, administrator,
 *       system and anonymous actors.
 *   <li>{@link com.memetrade.audit.api.AuditActions}: names of the audited actions of U1 and U2.
 *   <li>{@link com.memetrade.audit.api.AuditPublishRejectedException}: refusal of an invalid event (a defect of the
 *       calling module), which rolls the action back.
 * </ul>
 *
 * <p>The administrator query ({@code GET /api/v1/admin/audit-events}), the 12-month retention purge and the outbox
 * cleanup are internal to the module ({@code domain}, {@code infra}, {@code web}); see docs/api/c8-admin-audit-events.md
 * and docs/adr/ADR-0002 and ADR-0003.
 */
package com.memetrade.audit.api;
