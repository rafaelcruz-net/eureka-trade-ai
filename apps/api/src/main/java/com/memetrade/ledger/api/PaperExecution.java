package com.memetrade.ledger.api;

import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperEnums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * C15, U7 for U5: the PAPER execution. Every operation requires the cycle transaction opened by U5 (propagation
 * {@code MANDATORY}, BR2.1) and writes everything or nothing; tenant and person come from the authentication context
 * of the cycle (C10), never from the arguments. Domain refusals are explicit results recorded and committed with the
 * cycle; only a technical failure throws, and U5 rolls the whole cycle back without retrying inside it.
 *
 * <p>PAPER never builds, signs nor sends a transaction (BR1.9).
 */
public interface PaperExecution {

    /**
     * F1: the initial virtual balance of the bot, once per bot (BR2.6), through the common idempotency mechanism
     * (operation {@code PAPER_FUND}).
     *
     * @param amount USDC, scale 6, greater than zero
     */
    FundResult fund(UUID botId, BigDecimal amount, String idempotencyKey);

    /** F2: one order approved by the barrier of U6, deduplicated by its own key (BR3.1 to BR3.3). */
    ExecutionResult execute(PaperOrderRequest order, String idempotencyKey);

    /**
     * F3: closes every open position of the bot at the reference price (BR5.1) or leaves it pending (BR5.2), through
     * the common idempotency mechanism (operation {@code PAPER_CLOSE_POSITIONS}).
     *
     * @param limitsVersion the limits version in force at the closing, recorded in every CLOSE order (BR3.4)
     */
    CloseResult closePositions(UUID botId, long limitsVersion, String idempotencyKey);

    /** The order U5 hands over after the decision and the barrier approved it. */
    record PaperOrderRequest(
            UUID botId,
            UUID decisionId,
            OrderSide side,
            String chain,
            String tokenMint,
            BigDecimal requestedQuantity,
            long limitsVersion) {

        public PaperOrderRequest {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(chain, "chain");
            Objects.requireNonNull(tokenMint, "tokenMint");
            Objects.requireNonNull(requestedQuantity, "requestedQuantity");
            if (requestedQuantity.signum() <= 0) {
                throw new IllegalArgumentException("requestedQuantity must be greater than zero");
            }
        }
    }

    /** Every hypothesis of a simulated fill (FR9.1, AC4.11.1), as recorded in {@code fill}. */
    record FillDetails(
            UUID fillId,
            BigDecimal filledQuantity,
            BigDecimal referencePrice,
            BigDecimal quotedPrice,
            BigDecimal fillPrice,
            BigDecimal spreadPct,
            BigDecimal impactPct,
            BigDecimal slippagePct,
            BigDecimal liquidityConsumedPct,
            BigDecimal liquidityUsed,
            BigDecimal grossAmount,
            BigDecimal fees,
            Optional<BigDecimal> realized,
            String marketSource,
            Instant marketObservedAt,
            String simulationPolicyVersion,
            Instant filledAt) {}

    /** Result of {@code execute} (C15 with the U7 additions). */
    sealed interface ExecutionResult
            permits ExecutionResult.Filled,
                    ExecutionResult.NotFilled,
                    ExecutionResult.Duplicate,
                    ExecutionResult.Conflict {

        /** FILLED or PARTIALLY_FILLED, with the requested and the filled quantity. */
        record Filled(
                UUID orderId,
                OrderStatus status,
                BigDecimal requestedQuantity,
                BigDecimal filledQuantity,
                FillDetails fill)
                implements ExecutionResult {}

        /**
         * The order was recorded NOT_FILLED with the reason, and the cycle commits (it is not an error). The order id
         * is empty only for {@code TENANT_MISMATCH}, which records no order (only {@code REQUEST_FORBIDDEN}).
         */
        record NotFilled(Optional<UUID> orderId, NotFilledReason reason) implements ExecutionResult {}

        /** Same key, same content: the recorded result of the first execution; nothing new is written (BR3.2). */
        record Duplicate(ExecutionResult original) implements ExecutionResult {}

        /** Same key, different content: nothing is written (BR3.3). */
        record Conflict(String idempotencyKey) implements ExecutionResult {}
    }

    /** Result of {@code fund}. */
    sealed interface FundResult permits FundResult.Funded, FundResult.Conflict {

        /** {@code replayed} is true when the stored result of the same key and content was returned. */
        record Funded(UUID botId, BigDecimal amount, UUID entryId, boolean replayed) implements FundResult {}

        /** {@code DIFFERENT_CONTENT} (same key, other amount or bot) or {@code ALREADY_FUNDED} (other key). */
        record Conflict(String reason) implements FundResult {}
    }

    /** Result of {@code closePositions}. */
    sealed interface CloseResult permits CloseResult.Closed, CloseResult.Conflict, CloseResult.Refused {

        record Closed(
                UUID botId,
                AccountStatus accountStatus,
                List<ClosedPosition> closed,
                List<PendingPosition> pending,
                boolean replayed)
                implements CloseResult {

            public Closed {
                closed = List.copyOf(closed);
                pending = List.copyOf(pending);
            }
        }

        /** Same key with other content. */
        record Conflict(String reason) implements CloseResult {}

        /** The bot belongs to another person or tenant (BR9.1): {@code TENANT_MISMATCH}, audited. */
        record Refused(NotFilledReason reason) implements CloseResult {}

        record ClosedPosition(
                UUID positionId,
                UUID orderId,
                String chain,
                String tokenMint,
                BigDecimal quantity,
                BigDecimal realized) {}

        record PendingPosition(UUID positionId, UUID orderId, String chain, String tokenMint, BigDecimal quantity) {}
    }
}
