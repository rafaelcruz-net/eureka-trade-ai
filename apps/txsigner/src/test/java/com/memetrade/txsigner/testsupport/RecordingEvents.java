package com.memetrade.txsigner.testsupport;

import com.memetrade.txsigner.domain.SignerEvent;
import com.memetrade.txsigner.domain.SignerEvents;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every published audit event for the assertions. */
public final class RecordingEvents implements SignerEvents {

    private final List<SignerEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(SignerEvent event) {
        events.add(event);
    }

    public List<SignerEvent> all() {
        return List.copyOf(events);
    }

    public List<SignerEvent.Type> types() {
        return events.stream().map(SignerEvent::type).toList();
    }
}
