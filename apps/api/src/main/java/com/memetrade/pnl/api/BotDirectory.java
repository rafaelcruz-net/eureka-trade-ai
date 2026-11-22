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
}
