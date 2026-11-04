package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.domain.CompensationPort.OriginalEntry;
import java.util.UUID;

/**
 * Domain mechanism of the compensating entry (F10, BR4.2): no route, no screen, only tests call it in the beta.
 * Package-private on purpose (NFR6.4): no class outside {@code ledger.domain} can reach it (ArchUnit). In the beta only
 * a FEE is compensated (finding of the design review): any other type is refused, and the reconciliation recomputes a
 * position by replaying the fills, so a compensation never has to touch a position. An entry is compensated at most
 * once and a COMPENSATION is never compensated; the database enforces both again.
 */
final class LedgerCompensation {

    private final CompensationPort port;

    LedgerCompensation(CompensationPort port) {
        this.port = port;
    }

    /** Writes COMPENSATION = -original.amount; returns the new entry id. */
    UUID compensate(UUID entryId, String reason) {
        OriginalEntry original =
                port.find(entryId).orElseThrow(() -> new CompensationRefused("Unknown entry " + entryId));
        if (original.type() != EntryType.FEE) {
            throw new CompensationRefused("Only a FEE entry is compensated in the beta");
        }
        if (port.alreadyCompensated(entryId)) {
            throw new CompensationRefused("The entry " + entryId + " was already compensated");
        }
        return port.record(original, original.amount().negate(), reason);
    }

    /** A compensation refused by the rule (BR4.2): unknown, already compensated, or not a FEE in the beta. */
    static final class CompensationRefused extends RuntimeException {
        CompensationRefused(String message) {
            super(message);
        }
    }
}
