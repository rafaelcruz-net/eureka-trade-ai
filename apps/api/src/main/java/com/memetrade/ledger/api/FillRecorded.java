package com.memetrade.ledger.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event of the ledger (logical-components.md): published SYNCHRONOUSLY, inside the same transaction, after each
 * fill or close, with the account already locked. A failure of a listener rolls the whole transaction back. The
 * {@code pnl} module listens to recompute the equity peak (BR7.3) and never reads the ledger tables directly.
 */
public record FillRecorded(UUID tenantId, UUID ownerUserId, UUID botId, UUID orderId, Instant filledAt) {}
