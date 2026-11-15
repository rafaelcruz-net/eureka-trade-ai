package com.memetrade.realexecution.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M6 {@code recordDecision} (entities.md RealDecisionRequest): one approved REAL decision of a bot cycle. Lamports and
 * base units are integers, never floating point.
 *
 * @param approvalId the reservation of the barrier, only for a BUY (BR6.2 of the U3)
 * @param inputLamports lamports spent by a BUY; {@code null} for a SELL
 * @param tokenBaseUnits quantity sold by a SELL; {@code null} for a BUY
 * @param reason the reason of the bot, shown on the signing screen (AC3.2.1), at most 500 characters
 */
public record RealDecision(
        UUID tenantId,
        UUID userId,
        UUID botId,
        UUID decisionId,
        Optional<UUID> approvalId,
        RealSide side,
        String mint,
        Long inputLamports,
        Long tokenBaseUnits,
        String reason) {

    private static final Pattern BASE58_ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    public RealDecision {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(reason, "reason");
        if (!BASE58_ADDRESS.matcher(mint).matches()) {
            throw new IllegalArgumentException("mint is not a Base58 address");
        }
        if (side == RealSide.BUY) {
            if (inputLamports == null || inputLamports < 1 || tokenBaseUnits != null || approvalId.isEmpty()) {
                throw new IllegalArgumentException("a BUY spends at least 1 lamport and carries its approval");
            }
        } else if (tokenBaseUnits == null || tokenBaseUnits < 1 || inputLamports != null || approvalId.isPresent()) {
            throw new IllegalArgumentException("a SELL sells at least 1 base unit and has no approval");
        }
        reason = reason.length() > 500 ? reason.substring(0, 500) : reason;
    }
}
