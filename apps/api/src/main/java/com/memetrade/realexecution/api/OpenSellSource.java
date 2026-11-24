package com.memetrade.realexecution.api;

/**
 * 261121-paper-real-unico U4 (C16 extended by the decision D1 of the owner, ENT-405, BR1.3): where an open sale of a bot
 * is: a SELL order of the bot in progress ({@code ORDER}: PROPOSED, QUOTED, SIGNED, SENT or AMBIGUOUS) or a SELL
 * decision of the bot still in the queue ({@code DECISION_REQUEST}: PENDING, deferred ones included).
 */
public enum OpenSellSource {
    ORDER,
    DECISION_REQUEST
}
