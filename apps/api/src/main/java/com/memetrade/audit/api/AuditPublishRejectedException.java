package com.memetrade.audit.api;

import java.util.Objects;

/**
 * The publisher refused an audit event before writing it (BR1.2, BR1.4): a defect of the calling module, never a
 * user error. Thrown inside the caller's transaction, so the audited action rolls back with it (NFR4.7). The message
 * names the field and the reason, never the refused value.
 */
public class AuditPublishRejectedException extends IllegalArgumentException {

    /** Why the event was refused; also the {@code reason} label of {@code audit_publish_rejected_total}. */
    public enum Reason {
        /** A required field is absent or blank (BR1.2). */
        MISSING_FIELD,
        /** A field breaks its format: action not upper case, too long, or a PERSON event without tenant (BR1.2). */
        INVALID_FORMAT,
        /** {@code details} carries a key that is never accepted, such as {@code nonce} or {@code token} (BR1.4). */
        FORBIDDEN_KEY,
        /** A value looks like a JWT, a Bearer credential or a Base58 64-byte signature (BR1.4). */
        FORBIDDEN_VALUE,
        /**
         * u2-retention (BR2.3): a detail or the actor carries a full wallet address (a Base58 value of 32 to 44
         * characters that decodes to 32 bytes); the wallet travels only in the command's own field (BR2.1).
         */
        FULL_WALLET,
        /** {@link AuditEventPublisher#publish(AuditEventCommand)} was called outside a transaction (C9). */
        NO_TRANSACTION
    }

    private final String field;
    private final Reason reason;

    /**
     * @param field name of the refused field (never its value)
     * @param reason why it was refused
     */
    public AuditPublishRejectedException(String field, Reason reason) {
        super("Audit event rejected: field=" + field + ", reason=" + reason);
        this.field = Objects.requireNonNull(field, "field");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** Name of the refused field, for example {@code details.nonce} or {@code action}; never the value. */
    public String field() {
        return field;
    }

    /** Why the event was refused. */
    public Reason reason() {
        return reason;
    }
}
