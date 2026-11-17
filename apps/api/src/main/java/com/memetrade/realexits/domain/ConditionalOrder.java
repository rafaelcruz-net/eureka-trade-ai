package com.memetrade.realexits.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * entities.md ConditionalOrder: one conditional exit of a REAL position of the bot wallet. Prices in exact decimals,
 * the amount in base units of the token (the whole position at the creation, BR3.7). The bot wallet is kept by its id;
 * its address is read from M1 when the provider needs it, so the anonymization leaves no address here.
 */
public record ConditionalOrder(
        UUID conditionalOrderId,
        UUID tenantId,
        UUID userId,
        UUID botWalletId,
        String positionMint,
        ConditionalKind kind,
        Optional<BigDecimal> stopPriceUsd,
        Optional<BigDecimal> takeProfitPriceUsd,
        long amountBaseUnits,
        int slippageBps,
        ConditionalState state,
        AdapterKind adapter,
        Optional<String> providerOrderId,
        Optional<String> createSignature,
        Optional<String> fillSignature,
        Optional<String> cancelSignature,
        Optional<String> escrowAccount,
        Optional<ExitSide> filledSide,
        String idempotencyKey,
        Optional<String> signingMessage,
        Optional<Instant> signingDeadline,
        Optional<CancelReason> cancelReason,
        int cancelAttempts,
        Optional<Instant> lastReadAt,
        Optional<Instant> cancellingSince,
        Optional<Instant> filledAt,
        Instant createdAt,
        Instant updatedAt) {

    public ConditionalOrder {
        Objects.requireNonNull(conditionalOrderId, "conditionalOrderId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(botWalletId, "botWalletId");
        Objects.requireNonNull(positionMint, "positionMint");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** What the provider needs to read or cancel this conditional. */
    public TriggerPort.OrderRef ref(String botWalletAddress) {
        return new TriggerPort.OrderRef(
                conditionalOrderId, botWalletAddress, positionMint, providerOrderId, cancelSignature);
    }

    /** The signature shown to the person: the fill when it filled, otherwise the creation (contract R5). */
    public Optional<String> txSignature() {
        return fillSignature.isPresent() ? fillSignature : createSignature;
    }
}
