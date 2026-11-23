package com.memetrade.pnl.real;

import com.memetrade.ledger.api.CostFill;
import com.memetrade.ledger.api.CostHolder;
import com.memetrade.ledger.api.NetworkOrderKey;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealPosition;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 261121-paper-real-unico U2 (C11 implemented by the P&L; BR2.1 to BR2.4, BR2.6, BR3.1, BR4.1, BR4.3, BR4.4, BR5.3,
 * decisions D1 and D2): the one REAL average cost, per holder. Pure: no I/O, no clock, no floating point; {@code long}
 * with exact operations and {@link BigInteger} for the products.
 *
 * <ul>
 *   <li>A buy adds its quantity and its cost (what it paid for the token: SOL leg and RENT of the buy) to its one holder.
 *   <li>A holder that gives {@code s} of its {@code q} units with cost {@code c} removes {@code c} when {@code s = q}
 *       (zeroed, cost 0) and {@code ceil(c x s / q)} otherwise; the rest of the rounding stays on the open lot (BR2.2).
 *   <li>An own sale: the seller gives min(sold, held); the excess is shared among every OTHER holder with quantity,
 *       UNOWNED included, by quantity with the largest remainder (BR4.4, D2).
 *   <li>A sale without bot (liquidation, conditional): shared among every holder with quantity by the quantity just
 *       before it (BR4.3).
 *   <li>What passes every recorded position belongs to UNOWNED without cost and opens nothing (BR5.3).
 * </ul>
 *
 * The cost of the wallet in a mint is the sum of its holders: there is no second average cost (BR2.4). In phase 1 the
 * sharing is of the QUANTITY only; the received lamports and the fees are shared by RealProration (phase 2).
 */
public final class RealCostBasis implements RealCostMethod {

    @Override
    public Accumulator start() {
        return new Wallet();
    }

    private static final class Lot {
        long units;
        long cost;
    }

    private static final class Wallet implements Accumulator {

        /** mint -> holder (in tie-break order) -> lot. */
        private final Map<String, TreeMap<CostHolder, Lot>> lots = new LinkedHashMap<>();

        private NetworkOrderKey last;

        @Override
        public List<CostPart> apply(CostFill fill) {
            Objects.requireNonNull(fill, "fill");
            if (last != null && fill.key().compareTo(last) <= 0) {
                throw new IllegalArgumentException("a fill comes after the previous one in network order (C11)");
            }
            last = fill.key();
            TreeMap<CostHolder, Lot> mint = lots.computeIfAbsent(fill.mint(), m -> new TreeMap<>());
            if (fill.kind() == RealEntryKind.SWAP_FILL_IN) {
                Lot lot = mint.computeIfAbsent(fill.holder().orElseThrow(), h -> new Lot());
                lot.units = Math.addExact(lot.units, fill.tokenBaseUnits());
                lot.cost = Math.addExact(lot.cost, Math.negateExact(fill.lamports()));
                return List.of();
            }
            return sale(mint, fill);
        }

        /** The quantity of the sale by {@link RealProration#split} (BR2.3, BR4.3, BR4.4, BR5.3), then the cost. */
        private List<CostPart> sale(TreeMap<CostHolder, Lot> mint, CostFill fill) {
            List<RealProration.Holding> held = new ArrayList<>();
            for (Map.Entry<CostHolder, Lot> entry : mint.entrySet()) {
                held.add(new RealProration.Holding(entry.getKey(), entry.getValue().units, entry.getValue().cost));
            }
            List<CostPart> parts = new ArrayList<>();
            for (RealProration.QuantityPart quantity :
                    RealProration.split(fill.holder(), fill.tokenBaseUnits(), held)) {
                parts.add(
                        quantity.withoutCost()
                                ? new CostPart(Holder.UNOWNED, quantity.units(), 0, true)
                                : give(quantity.holder(), mint.get(quantity.holder()), quantity.units()));
            }
            mint.values().removeIf(lot -> lot.units == 0);
            return parts;
        }

        /** BR2.2: all the cost at zero; otherwise rounded up, the rest staying on the open lot. */
        private static CostPart give(CostHolder holder, Lot lot, long units) {
            long removed = RealProration.costRemoved(lot.units, lot.cost, units);
            lot.units = Math.subtractExact(lot.units, units);
            lot.cost = lot.units == 0 ? 0 : Math.subtractExact(lot.cost, removed);
            return new CostPart(holder, units, removed, false);
        }

        @Override
        public List<RealPosition> openPositions() {
            List<RealPosition> open = new ArrayList<>();
            lots.forEach((mint, holders) -> {
                long units = 0;
                long cost = 0;
                for (Lot lot : holders.values()) {
                    units = Math.addExact(units, lot.units);
                    cost = Math.addExact(cost, lot.cost);
                }
                if (units > 0) {
                    open.add(new RealPosition(mint, units, new RealCost.Known(cost)));
                }
            });
            return open;
        }

        @Override
        public List<RealPosition> openPositions(CostHolder holder) {
            List<RealPosition> open = new ArrayList<>();
            lots.forEach((mint, holders) -> {
                Lot lot = holders.get(holder);
                if (lot != null && lot.units > 0) {
                    open.add(new RealPosition(mint, lot.units, new RealCost.Known(lot.cost)));
                }
            });
            return open;
        }
    }
}
