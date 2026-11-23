package com.memetrade.pnl.real;

import com.memetrade.ledger.api.CostHolder;
import com.memetrade.ledger.api.RealCostMethod.CostPart;
import com.memetrade.pnl.real.ProrationPart.Origin;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U2 (BR2.2, BR2.3, BR4.1 to BR4.8, BR5.2, BR5.3, BR6.2, BR6.4, BR7.1; decisions Q1, Q2, Q3,
 * D2): the one way a REAL sale is shared among the holders of a bot wallet. Pure: no I/O, no clock, no floating point;
 * {@code long} with exact operations and {@link BigInteger} for the products. Every share of an integer is the largest
 * remainder ({@link LargestRemainder}) in the tie-break order of the holders (BR4.2: older bot, then smaller id,
 * UNOWNED last).
 *
 * <ol>
 *   <li><b>Quantity</b> ({@link #split}): an own sale gives first the quantity of its seller (never more than it holds,
 *       BR2.3); the excess, or the whole quantity of a sale without bot, is shared among every OTHER holder with
 *       quantity, the part without owner included, by the quantity just before the sale (BR4.3, BR4.4, D2); what passes
 *       every recorded position belongs to UNOWNED without cost (BR5.3). A holder without quantity gets nothing (BR4.8).
 *   <li><b>Cost</b> ({@link #costRemoved}): all the cost when the holder gives all it holds, otherwise
 *       {@code ceil(cost x given / held)} (BR2.2).
 *   <li><b>Money</b> ({@link #parts}): the received lamports and the fee are shared separately by the quantity each
 *       participant gave (BR4.5); {@code realized = received - cost} (BR7.1); the origin of each part (BR4.7).
 * </ol>
 *
 * {@code RealCostBasis} uses {@link #split} and {@link #costRemoved}, so the cost path and the P&L share one algorithm.
 */
public final class RealProration {

    private RealProration() {}

    /** What kind of sale is shared. */
    public enum SaleKind {
        /** The sale of a bot (or of UNOWNED when its order is gone, BR5.4). */
        OWN_SALE,
        /** A sale of the "vender tudo e sacar" (origin USER_LIQUIDATION). */
        LIQUIDATION,
        /** The fill of a conditional ({@code conditional:<id>}). */
        CONDITIONAL
    }

    /** The position of a holder just before an event: quantity and cost, both at least 0. */
    public record Holding(CostHolder holder, long units, long cost) {
        public Holding {
            Objects.requireNonNull(holder, "holder");
            if (units < 0 || cost < 0) {
                throw new IllegalArgumentException("a holding is never negative");
            }
        }
    }

    /** The quantity one holder gives in a sale; {@code withoutCost} for what passed every recorded position. */
    public record QuantityPart(CostHolder holder, long units, boolean withoutCost) {
        public QuantityPart {
            Objects.requireNonNull(holder, "holder");
            if (units < 1) {
                throw new IllegalArgumentException("a quantity part gives at least 1 base unit");
            }
            if (withoutCost && !holder.unowned()) {
                throw new IllegalArgumentException("only UNOWNED takes a quantity without cost");
            }
        }
    }

    /**
     * BR2.3, BR4.3, BR4.4, BR4.8, BR5.3: who gives how much of a sale of {@code sold}.
     *
     * @param seller the seller of an own sale; empty for a sale without bot
     * @param held the holders of the mint just before the sale, in any order (those at 0 give nothing)
     * @return the seller first, then the other holders in tie-break order, then the part without cost
     */
    public static List<QuantityPart> split(Optional<CostHolder> seller, long sold, List<Holding> held) {
        Objects.requireNonNull(seller, "seller");
        if (sold < 1) {
            throw new IllegalArgumentException("a sale gives at least 1 base unit");
        }
        List<Holding> ordered = new ArrayList<>(held);
        ordered.sort(Comparator.comparing(Holding::holder));
        List<QuantityPart> parts = new ArrayList<>();
        long left = sold;
        if (seller.isPresent()) {
            long own = ordered.stream()
                    .filter(h -> h.holder().equals(seller.get()))
                    .mapToLong(Holding::units)
                    .sum();
            long given = Math.min(left, own);
            if (given > 0) {
                parts.add(new QuantityPart(seller.get(), given, false));
                left = Math.subtractExact(left, given);
            }
        }
        if (left > 0) {
            List<CostHolder> participants = new ArrayList<>();
            List<Long> weights = new ArrayList<>();
            long others = 0;
            for (Holding holding : ordered) {
                if (holding.units() > 0
                        && !seller.map(s -> s.equals(holding.holder())).orElse(false)) {
                    participants.add(holding.holder());
                    weights.add(holding.units());
                    others = Math.addExact(others, holding.units());
                }
            }
            long shared = Math.min(left, others);
            if (shared > 0) {
                long[] quantities = LargestRemainder.share(
                        shared, weights.stream().mapToLong(Long::longValue).toArray());
                for (int i = 0; i < participants.size(); i++) {
                    if (quantities[i] > 0) {
                        parts.add(new QuantityPart(participants.get(i), quantities[i], false));
                    }
                }
            }
            long withoutCost = Math.subtractExact(left, shared);
            if (withoutCost > 0) {
                parts.add(new QuantityPart(Holder.UNOWNED, withoutCost, true));
            }
        }
        return parts;
    }

    /**
     * BR2.2 (Q1): the cost a holder with {@code units} and {@code cost} removes when it gives {@code given}: everything
     * at zero, otherwise rounded UP; the rest of the rounding stays on the open lot.
     */
    public static long costRemoved(long units, long cost, long given) {
        if (given < 1 || given > units || cost < 0) {
            throw new IllegalArgumentException("a holder gives between 1 base unit and what it holds");
        }
        if (given == units) {
            return cost;
        }
        return BigInteger.valueOf(cost)
                .multiply(BigInteger.valueOf(given))
                .add(BigInteger.valueOf(units - 1))
                .divide(BigInteger.valueOf(units))
                .longValueExact();
    }

    /**
     * The whole sale from the holdings just before it: {@link #split}, {@link #costRemoved}, then {@link #parts}.
     *
     * @param before the holders of the mint just before the sale
     */
    public static List<ProrationPart> sale(
            SaleKind kind, Optional<CostHolder> seller, List<Holding> before, long sold, long received, long fee) {
        List<CostPart> costParts = new ArrayList<>();
        for (QuantityPart quantity : split(seller, sold, before)) {
            if (quantity.withoutCost()) {
                costParts.add(new CostPart(quantity.holder(), quantity.units(), 0, true));
                continue;
            }
            Holding holding = before.stream()
                    .filter(h -> h.holder().equals(quantity.holder()))
                    .findFirst()
                    .orElseThrow();
            costParts.add(new CostPart(
                    quantity.holder(),
                    quantity.units(),
                    costRemoved(holding.units(), holding.cost(), quantity.units()),
                    false));
        }
        return parts(kind, seller, costParts, received, fee);
    }

    /**
     * BR4.5, BR4.7, BR6.1, BR6.4: the received lamports and the fee of a sale shared by the quantity each participant gave
     * (the cost parts of the accumulator), each with its origin.
     *
     * @return one part per cost part, in tie-break order, the part without cost last
     */
    public static List<ProrationPart> parts(
            SaleKind kind, Optional<CostHolder> seller, List<CostPart> costParts, long received, long fee) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(seller, "seller");
        if (received < 0 || fee < 0) {
            throw new IllegalArgumentException("the received and the fee of a sale are never negative");
        }
        if (costParts.isEmpty()) {
            throw new IllegalArgumentException("a sale has at least one part");
        }
        List<CostPart> ordered = new ArrayList<>(costParts);
        ordered.sort(Comparator.comparing(CostPart::withoutCost).thenComparing(CostPart::holder));
        long[] weights = ordered.stream().mapToLong(CostPart::tokenBaseUnits).toArray();
        long[] receivedParts = LargestRemainder.share(received, weights);
        long[] feeParts = LargestRemainder.share(fee, weights);
        List<ProrationPart> parts = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            CostPart part = ordered.get(i);
            parts.add(new ProrationPart(
                    part.holder(),
                    part.tokenBaseUnits(),
                    receivedParts[i],
                    feeParts[i],
                    part.costRemovedLamports(),
                    Math.subtractExact(receivedParts[i], part.costRemovedLamports()),
                    origin(kind, seller, part)));
        }
        return parts;
    }

    /**
     * BR6.2: the fee of a failed sale without bot shared by the quantities of the holders just before it, as parts of
     * the origin of the sale with no quantity and nothing received. Empty when nobody holds the mint (the caller puts
     * the fee on the line "Sem bot" with UNOWNED_NO_COST).
     */
    public static List<ProrationPart> fee(SaleKind kind, List<Holding> before, long fee) {
        if (kind == SaleKind.OWN_SALE) {
            throw new IllegalArgumentException("the fee of a failed order of a bot stays whole on the bot (BR6.2)");
        }
        if (fee < 0) {
            throw new IllegalArgumentException("a fee is never negative");
        }
        List<Holding> holders =
                new ArrayList<>(before.stream().filter(h -> h.units() > 0).toList());
        if (holders.isEmpty()) {
            return List.of();
        }
        holders.sort(Comparator.comparing(Holding::holder));
        long[] feeParts = LargestRemainder.share(
                fee, holders.stream().mapToLong(Holding::units).toArray());
        List<ProrationPart> parts = new ArrayList<>();
        for (int i = 0; i < holders.size(); i++) {
            CostHolder holder = holders.get(i).holder();
            Origin origin = holder.unowned()
                    ? Origin.UNOWNED_EXCESS
                    : kind == SaleKind.LIQUIDATION ? Origin.LIQUIDATION : Origin.CONDITIONAL;
            parts.add(new ProrationPart(holder, 0, 0, feeParts[i], 0, 0, origin));
        }
        return parts;
    }

    /** BR4.7, BR5.2, BR5.3. */
    private static Origin origin(SaleKind kind, Optional<CostHolder> seller, CostPart part) {
        if (part.withoutCost()) {
            return Origin.UNOWNED_NO_COST;
        }
        if (kind == SaleKind.OWN_SALE
                && seller.map(s -> s.equals(part.holder())).orElse(false)) {
            return Origin.OWN;
        }
        if (part.holder().unowned()) {
            return Origin.UNOWNED_EXCESS;
        }
        return switch (kind) {
            case OWN_SALE -> Origin.SOLD_BY_OTHER_BOT;
            case LIQUIDATION -> Origin.LIQUIDATION;
            case CONDITIONAL -> Origin.CONDITIONAL;
        };
    }
}
