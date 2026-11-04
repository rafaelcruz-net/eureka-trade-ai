package com.memetrade.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event of the ledger, published SYNCHRONOUSLY in the transaction of {@code fund} (F1): the {@code pnl} module
 * listens and creates the initial equity peak equal to the FUNDING amount (BR2.6), without the ledger writing a table
 * of {@code pnl}.
 */
public record PaperAccountFunded(UUID tenantId, UUID ownerUserId, UUID botId, BigDecimal amount, Instant fundedAt) {}
