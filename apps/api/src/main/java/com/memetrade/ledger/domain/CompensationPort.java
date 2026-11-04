package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.PaperEnums.EntryType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * What the compensation mechanism needs from persistence (F10), implemented by the ledger infrastructure: reading an
 * original entry and writing its COMPENSATION together with the fee correction of the daily summary and the audit
 * event, in the caller's transaction.
 */
public interface CompensationPort {

    Optional<OriginalEntry> find(UUID entryId);

    boolean alreadyCompensated(UUID entryId);

    /** Writes COMPENSATION = {@code amount} referencing the original and returns its entry id. */
    UUID record(OriginalEntry original, BigDecimal amount, String reason);

    /** An entry as the compensation reads it. */
    record OriginalEntry(UUID entryId, UUID botId, EntryType type, BigDecimal amount, String referenceId) {}
}
