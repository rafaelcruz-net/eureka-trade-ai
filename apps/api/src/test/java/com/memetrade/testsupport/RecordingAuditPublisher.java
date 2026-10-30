package com.memetrade.testsupport;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Unit-test double of the C9 publisher that keeps the published events in memory. */
public final class RecordingAuditPublisher implements AuditEventPublisher {

    private final List<AuditEventCommand> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AuditEventCommand event) {
        events.add(event);
    }

    public List<AuditEventCommand> events() {
        return List.copyOf(events);
    }

    public List<AuditEventCommand> withAction(String action) {
        return events.stream().filter(e -> e.action().equals(action)).toList();
    }
}
