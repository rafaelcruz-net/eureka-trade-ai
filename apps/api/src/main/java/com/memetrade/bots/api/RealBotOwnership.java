package com.memetrade.bots.api;

import java.util.UUID;

/**
 * 261113-trading-real-bot U4 (BR1.2, BR2.1, adendo da U3 "Posse do bot"): what the REAL execution may know of a bot
 * without reading a table of Bots. The consumer of the decision queue checks the ownership again before creating the
 * order.
 */
public interface RealBotOwnership {

    /** The bot is a REAL bot of this person in this tenant, ACTIVE and without a pause reason. */
    boolean activeRealBotOf(UUID tenantId, UUID userId, UUID botId);
}
