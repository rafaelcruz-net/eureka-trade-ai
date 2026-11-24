package com.memetrade.bots.domain;

/**
 * 261121-paper-real-unico U4 (ENT-404 split by the alignment A11): why the REAL cycle could not sell by the own quantity
 * of the bot. The first three are the reasons of the read of C15 ({@code pnl.api.UnavailableReason}); STALE_SNAPSHOT is
 * the cycle's own: the mark of the bot changed between the read and the lock (BR3.1). The reason of the note of the
 * decision and of the logs; never a label of a metric with the id of a bot.
 */
public enum OwnQuantityReason {
    BUDGET_EXCEEDED,
    READ_FAILED,
    BOT_NOT_OWNED,
    STALE_SNAPSHOT
}
