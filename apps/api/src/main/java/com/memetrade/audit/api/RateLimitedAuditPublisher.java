package com.memetrade.audit.api;

import java.time.Instant;

/**
 * 261121-paper-real-unico U2 (NFR3.15, R-09, security-design.md "REAL_PNL_RECONCILIATION_MISMATCH com limite de 1 por
 * hora"): one audit event written at most once per hour for the same person and fingerprint, read from the audit trail
 * itself (the pending outbox and {@code audit_event}), under an advisory lock of the person and fingerprint, in a
 * transaction of a pool of its own, never the main one. Nothing is kept in memory nor in a table of the caller.
 *
 * <p>Used only by the P&L: the P&L REAL (the reconciliation mismatch) and, since 261121-paper-real-unico U3 (D-NFR1,
 * alignment A10), the refused reads of the screens of the P&L ({@code ReadRefusalAudit}: the 404 of a bot of another
 * tenant and the 422 {@code TEMPLATE_MISMATCH}); the caller never waits more than its own budget and never changes its
 * answer because of this write.
 */
public interface RateLimitedAuditPublisher {

    /** What happened to the event. */
    enum Outcome {
        /** The event was written to the outbox. */
        WRITTEN,
        /** An event of the same person and fingerprint already exists in the last hour; nothing was written. */
        DEDUPLICATED,
        /** Another instance is writing the same event right now (the advisory lock is taken); nothing was written. */
        LOCK_BUSY
    }

    /** The deadline passed before the write could start (the connection came too late); nothing was written. */
    final class DeadlinePassed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DeadlinePassed() {
            super("The deadline of the audit write passed before it wrote");
        }
    }

    /**
     * @param fingerprint SHA-256 of the canonical text of the event (in {@code details.fingerprint} too)
     * @param deadline the instant of the injected clock after which the write gives up without writing
     * @throws RuntimeException any failure of the write (no connection in 250 ms, deadline passed, database error)
     * @throws AuditPublishRejectedException the event breaks a rule of the trail
     */
    Outcome publishOncePerHour(AuditEventCommand command, String fingerprint, Instant deadline);
}
