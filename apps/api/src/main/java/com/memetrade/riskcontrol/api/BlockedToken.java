package com.memetrade.riskcontrol.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 261113-trading-real-bot U3 (entities.md BlockedToken, BR2.3): a token on the block list of the administrator. The list
 * is global (not per tenant); a blocked token is never bought by any REAL bot, and its sale still passes (BR2.2).
 */
public record BlockedToken(String chain, String mint, String reason, UUID blockedBy, Instant blockedAt) {

    /** BR2.3: the reason is required and has at most 500 characters. */
    public static final int MAX_REASON = 500;

    public BlockedToken {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(blockedBy, "blockedBy");
        Objects.requireNonNull(blockedAt, "blockedAt");
    }
}
