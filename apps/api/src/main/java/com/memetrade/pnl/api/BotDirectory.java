package com.memetrade.pnl.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U1, C8 (ADR-007, BR4.1, BR8.1, BR8.2): the bots of a person as the P&L needs them, declared
 * here and implemented by the {@code bots} module (the dependency is {@code bots -> pnl.api}; the {@code pnl} never
 * depends on {@code bots.api}). Read only; it takes part in the transaction of the caller.
 */
public interface BotDirectory {

    /**
     * The bots of the person in this market, paused and removed included, drafts never, in order of creation
     * ({@code createdAt}, then {@code botId}). A tenant or person of somebody else gives an empty list.
     */
    List<BotEntry> botsOf(UUID tenantId, UUID userId, PnlMarket market);

    /** Empty when the bot is not the person's (or does not exist, or is a draft): the caller answers 404. */
    Optional<BotEntry> botOf(UUID tenantId, UUID userId, UUID botId);

    /**
     * 261121-paper-real-unico U3 (decision F-2 of the NFR Design; NFR3.13, NFR3.17): whether a bot with this id exists
     * in ANOTHER tenant. It is THE ONLY read of the directory outside the tenant of the caller, and the one exception to
     * the rule "every query of client data filters by the tenant": by the primary key only, it answers only a boolean and
     * never a field of the bot. Only the path of the 404 of the reads of the P&L ({@code PnlController}) calls it, on the
     * request thread, in BOTH cases of 404 (an id that does not exist and a bot of another tenant), so the time of the
     * answer does not tell one from the other; only a {@code true} goes to the audit (ArchUnit
     * {@code ONLY_THE_404_PATH_ASKS_OTHER_TENANTS}).
     */
    boolean ownedByAnotherTenant(UUID tenantId, UUID botId);
}
