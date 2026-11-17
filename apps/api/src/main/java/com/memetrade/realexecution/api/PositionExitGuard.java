package com.memetrade.realexecution.api;

import java.util.UUID;

/**
 * 261113-trading-real-bot U7 (BR4.3, U7-NFR4.3, logical-components.md "Venda atrás do PositionExitGuard"): declared by
 * the M6, implemented by {@code realexits}. Before every market sale of a position (the decision of the bot, the
 * liquidation and the signature of the person), the U4 asks whether the position is free to sell.
 *
 * <p>It runs in the transaction and under the person lock of the CALLER ({@code MANDATORY}); it never takes a lock and
 * never calls the network. It only reads the conditional of the position and, through the compare-and-set of its state
 * machine, cancels a PENDING_SIGNATURE one at once or marks an OPEN one CANCELLING ({@code MARKET_SELL}); the
 * cancellation job sends the cancellation outside the lock. After {@link ExitRelease#READY} the caller reads the
 * position again from the REAL ledger (a conditional may have FILLED it): zero ends the sale with
 * {@code NOTHING_TO_SELL}.
 */
@FunctionalInterface
public interface PositionExitGuard {

    ExitRelease releaseForSale(UUID tenantId, UUID userId, String mint);

    /** READY: no conditional holds the position any more; WAIT: its cancellation is in progress. */
    enum ExitRelease {
        READY,
        WAIT
    }
}
