package com.memetrade.ledger.api;

import java.util.List;

/**
 * C11 (261121-paper-real-unico U2; decision D1 of the founder, BR2.4, BR2.5, BR2.8): the one REAL average cost method,
 * a pure port (no I/O, no Spring, no clock, no floating point) with two uses:
 *
 * <ol>
 *   <li>the <b>quantity-only path</b> of the wallet, fed with its swap fills without attribution: implemented inside
 *       the ledger ({@code ledger.domain.RealPositions}) and used by {@link RealLedger#positions} and
 *       {@link RealLedger#view}, so a sale, a liquidation and the barrier never depend on the attribution;
 *   <li>the <b>cost accumulator per holder</b> ({@link #start()}), fed with {@link CostFill}s already attributed, in
 *       network order: implemented by the P&L ({@code pnl.real.RealCostBasis}), so the ledger never depends on the P&L.
 *       The cost of the wallet in a mint is the sum of the costs of its holders; there is no second average cost.
 * </ol>
 *
 * Rounding (BR2.2, BR2.6): the cost removed by a partial sale is rounded UP, the last sale takes what remains; the cost
 * left on a holder can be up to 1 lamport below the exact fraction per partial sale, and is exact again at zero.
 */
public interface RealCostMethod {

    /** An empty accumulator for one bot wallet. */
    Accumulator start();

    /** The cost positions of the holders of one bot wallet, applied one fill at a time. */
    interface Accumulator {

        /**
         * Applies one attributed fill.
         *
         * @return the parts of a sale, one per holder that gave quantity (empty for a buy)
         * @throws IllegalArgumentException when the fill does not come after the previous one in network order
         */
        List<CostPart> apply(CostFill fill);

        /** The open positions of the wallet: per mint, the sum of the quantities and of the costs of its holders. */
        List<RealPosition> openPositions();

        /** The open positions of one holder, quantity at least 1, cost at least 0. */
        List<RealPosition> openPositions(CostHolder holder);
    }

    /**
     * One part of a sale (BR2.2, BR4.3, BR4.4, BR5.3): the quantity a holder gave and the cost it removed. A part
     * {@code withoutCost} is the quantity sold beyond every recorded position; it belongs to UNOWNED, removes no cost and
     * opens nothing.
     */
    record CostPart(CostHolder holder, long tokenBaseUnits, long costRemovedLamports, boolean withoutCost) {

        public CostPart {
            java.util.Objects.requireNonNull(holder, "holder");
            if (tokenBaseUnits < 1 || costRemovedLamports < 0) {
                throw new IllegalArgumentException(
                        "a part gives at least 1 base unit and removes a cost of at least 0");
            }
            if (withoutCost && (costRemovedLamports != 0 || !holder.unowned())) {
                throw new IllegalArgumentException("a part without cost belongs to UNOWNED and removes nothing");
            }
        }
    }
}
