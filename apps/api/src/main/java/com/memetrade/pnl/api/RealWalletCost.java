package com.memetrade.pnl.api;

import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (BR2.4, BR2.7, BR2.8 item 3; performance-design.md "Leitura de custo com atribuição"): the
 * cost of one bot wallet, the sum of the costs of its holders, read with attribution: the REAL bots of the person (C8),
 * its settled orders (C13) and every row of the wallet in network order (C12), outside the quantity-only path and only
 * for the readers that need a cost: the rounds of the barrier ({@link CostPath#GATE}) and the REAL cycle
 * ({@link CostPath#CYCLE}). A buy, a sale, a liquidation and a withdrawal never ask it.
 *
 * <p>The read runs in a savepoint of the transaction of the caller, under a hard deadline; whatever fails, the savepoint
 * is rolled back and the transaction of the caller stays usable, and the answer is {@link WalletCost.Unknown}. Never a
 * lock.
 */
public interface RealWalletCost {

    /**
     * @param expectedWalletRows the {@code walletRowCount} of the view the caller read just before, in the same
     *     transaction; another count of rows streamed means another set of rows ({@code INCONSISTENT_READ})
     */
    WalletCost costOf(UUID tenantId, UUID userId, UUID botWalletId, CostPath path, long expectedWalletRows);

    /**
     * 261121-paper-real-unico U2a (Etapa 14a, decision of the founder): the cost from the epoch of each mint, the open
     * lots of {@code view} (read by its own single statement, so one snapshot), with attribution; same budget, same
     * savepoint, same failures. By default, the read of the whole history.
     */
    default WalletCost costOf(
            UUID tenantId, UUID userId, UUID botWalletId, CostPath path, com.memetrade.ledger.api.RealLedgerView view) {
        return costOf(tenantId, userId, botWalletId, path, view.walletRowCount());
    }
}
