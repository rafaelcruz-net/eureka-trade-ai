package com.memetrade.riskcontrol.api;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 261113-trading-real-bot U3 (contract M5, entities.md OrderIntent): one REAL order to evaluate. Tenant and person come
 * from the bot or from the authentication of the caller, never from a request body (U3-NFR6.1). Lamports and token
 * base units are integers (BR7.2).
 *
 * <ul>
 *   <li>BUY only with origin {@link OrderOrigin#BOT}, with {@code botId}, {@code decisionId} and
 *       {@code inputLamports} of at least 1;
 *   <li>SELL with origin BOT (with {@code botId} and {@code decisionId}) or {@link OrderOrigin#USER_LIQUIDATION}, with
 *       {@code tokenBaseUnits} of at least 1.
 * </ul>
 *
 * @param inputLamports lamports spent by a BUY; {@code null} for a SELL
 * @param tokenBaseUnits quantity sold by a SELL; {@code null} for a BUY
 */
public record OrderIntent(
        UUID tenantId,
        UUID userId,
        UUID botId,
        UUID decisionId,
        OrderOrigin origin,
        OrderSide side,
        String chain,
        String mint,
        Long inputLamports,
        Long tokenBaseUnits) {

    /** The only chain of the REAL in this release. */
    public static final String SOLANA = "solana";

    private static final Pattern BASE58_ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    public OrderIntent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(mint, "mint");
        if (chain.isBlank()) {
            throw new IllegalArgumentException("chain is required");
        }
        if (!BASE58_ADDRESS.matcher(mint).matches()) {
            throw new IllegalArgumentException("mint is not a Base58 address");
        }
        if (origin == OrderOrigin.BOT && (botId == null || decisionId == null)) {
            throw new IllegalArgumentException("a BOT order carries its bot and its decision");
        }
        if (side == OrderSide.BUY) {
            if (origin != OrderOrigin.BOT) {
                throw new IllegalArgumentException("only the bot buys (USER_LIQUIDATION only sells)");
            }
            if (inputLamports == null || inputLamports < 1) {
                throw new IllegalArgumentException("a BUY spends at least 1 lamport");
            }
        } else if (tokenBaseUnits == null || tokenBaseUnits < 1) {
            throw new IllegalArgumentException("a SELL sells at least 1 base unit");
        }
    }

    /** A buy decided by the cycle of a bot (F1). */
    public static OrderIntent botBuy(
            UUID tenantId, UUID userId, UUID botId, UUID decisionId, String mint, long inputLamports) {
        return new OrderIntent(
                tenantId, userId, botId, decisionId, OrderOrigin.BOT, OrderSide.BUY, SOLANA, mint, inputLamports, null);
    }

    /** A sell decided by the cycle of a bot (F2). */
    public static OrderIntent botSell(
            UUID tenantId, UUID userId, UUID botId, UUID decisionId, String mint, long tokenBaseUnits) {
        return new OrderIntent(
                tenantId,
                userId,
                botId,
                decisionId,
                OrderOrigin.BOT,
                OrderSide.SELL,
                SOLANA,
                mint,
                null,
                tokenBaseUnits);
    }

    /** The "vender tudo e sacar" of the person (F2, BR1.1); no decision of a bot. */
    public static OrderIntent userLiquidation(UUID tenantId, UUID userId, String mint, long tokenBaseUnits) {
        return new OrderIntent(
                tenantId,
                userId,
                null,
                null,
                OrderOrigin.USER_LIQUIDATION,
                OrderSide.SELL,
                SOLANA,
                mint,
                null,
                tokenBaseUnits);
    }

    public boolean isBuy() {
        return side == OrderSide.BUY;
    }
}
