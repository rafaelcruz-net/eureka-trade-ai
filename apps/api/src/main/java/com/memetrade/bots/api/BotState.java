package com.memetrade.bots.api;

/**
 * State of a PAPER bot (C4 {@code BotState}, entities.md). The names are the same in Java, JSON and the database. The
 * only accepted transitions are those of the annex "Transições do bot" (BR2.1); {@link #STOPPED} is terminal.
 */
public enum BotState {
    DRAFT,
    VALIDATED,
    ACTIVE,
    PAUSED,
    CLOSING_PENDING,
    STOPPED
}
