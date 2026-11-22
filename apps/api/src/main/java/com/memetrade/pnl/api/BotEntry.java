package com.memetrade.pnl.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 261121-paper-real-unico U1, C8 (ENT-006, ADR-007): the minimal projection of a bot for the P&L; nothing of its
 * parameters, limits nor balance.
 */
public record BotEntry(
        UUID botId, String name, PnlMarket market, String templateId, BotPnlStatus status, Instant createdAt) {

    public BotEntry {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
