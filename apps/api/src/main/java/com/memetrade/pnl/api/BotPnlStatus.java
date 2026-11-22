package com.memetrade.pnl.api;

/**
 * 261121-paper-real-unico U1, C8 (BR4.1, FR3.11): the state of a bot in the P&L. ACTIVE and CLOSING_PENDING are
 * ACTIVE, PAUSED is PAUSED and STOPPED is REMOVED; a draft (DRAFT, VALIDATED) never appears.
 */
public enum BotPnlStatus {
    ACTIVE,
    PAUSED,
    REMOVED
}
