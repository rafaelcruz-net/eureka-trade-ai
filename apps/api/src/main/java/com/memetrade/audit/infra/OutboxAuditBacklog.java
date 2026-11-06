package com.memetrade.audit.infra;

import com.memetrade.audit.api.AuditBacklog;
import org.springframework.stereotype.Component;

/** U8: the pending count of the outbox, from the same read the relay measures ({@link AuditOutboxRelay#lag()}). */
@Component
public class OutboxAuditBacklog implements AuditBacklog {

    private final AuditOutboxRelay relay;

    public OutboxAuditBacklog(AuditOutboxRelay relay) {
        this.relay = relay;
    }

    @Override
    public long pending() {
        return relay.lag().pending();
    }
}
