package com.memetrade.testsupport;

import com.memetrade.audit.infra.AuditOutboxRelay;

/**
 * Relays the pending audit outbox through the REAL relay of U2 after a U5 test. The U5 suites run with clocks in the
 * past and write hundreds of events (50 parallel repetitions, lock races); left pending, they would fill the batch of
 * 100 that the tests of other Units relay and read right after their own action.
 */
final class U5Outbox {

    /** At most 1000 batches (100.000 events); a larger backlog means a test is writing in a loop. */
    private static final int MAX_BATCHES = 1000;

    private U5Outbox() {}

    static void drain(AuditOutboxRelay relay) {
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            if (relay.relayBatch() == 0) {
                return;
            }
        }
        throw new IllegalStateException("The audit outbox still has events after " + MAX_BATCHES + " batches");
    }
}
