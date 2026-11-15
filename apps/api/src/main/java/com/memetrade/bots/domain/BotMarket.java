package com.memetrade.bots.domain;

/**
 * 261113-trading-real-bot U4 (BR1.1, Q1): the market of a bot, chosen at creation and never changed (the database
 * refuses a change). Every bot created before the U4 is PAPER; a REAL bot needs an ACTIVE bot wallet of its owner.
 */
public enum BotMarket {
    PAPER,
    REAL
}
