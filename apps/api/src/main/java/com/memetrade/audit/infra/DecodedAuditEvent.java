package com.memetrade.audit.infra;

import com.memetrade.audit.api.AuditEventCommand;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@code audit_outbox} payload read back by the relay (u2-retention, BR2.18): the event, without any wallet, and the
 * {@code walletHmac} of the payload when it has one (empty for older events and for events without a wallet).
 */
record DecodedAuditEvent(AuditEventCommand command, Optional<String> walletHmac) {

    DecodedAuditEvent {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(walletHmac, "walletHmac");
    }
}
