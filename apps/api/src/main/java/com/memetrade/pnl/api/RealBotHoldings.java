package com.memetrade.pnl.api;

import java.time.Duration;
import java.util.UUID;

/**
 * 261121-paper-real-unico U4 (C15; alignment A13): the own holdings of one REAL bot, declared by the P&L that owns the
 * attribution and read only by the REAL cycle of the worker, BEFORE its transaction opens (ArchUnit R1). One snapshot
 * of its own: a read-only REPEATABLE READ transaction that fixes the snapshot at its first query, the REAL bots of the
 * person (C8); the ledger since the epoch of each mint (C12), the settled orders the lots name (C13) and the open sales
 * of the bot (C16) all from it. Tenant and person come from the bot row of the worker and the owner bound to the cycle,
 * never from outside. No lock: the guarantee against a sale twice is the mark of the bot, read before and again under
 * the lock of the cycle (BR3.1), not a lock of the person.
 */
public interface RealBotHoldings {

    /**
     * The own holdings of {@code botId}, within {@code budget}: the {@code ReadDeadline} starts before the connection is
     * asked, applies what remains as the statement timeout before every statement and every fetch, and the read is
     * checked once more after its commit, so the wait for a connection counts. Every failure is
     * {@link HoldingsResult.Unavailable}; nothing is thrown, except an {@link IllegalStateException} when called inside
     * a transaction (a programming defect, never an unavailability).
     */
    HoldingsResult holdings(UUID tenantId, UUID userId, UUID botId, Duration budget);
}
