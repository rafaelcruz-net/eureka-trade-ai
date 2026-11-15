package com.memetrade.pnl.real;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BR6.2 (Q6, Q10; team.md "P&L com cenários determinísticos"): the P&L REAL by average cost per mint, in exact integers
 * (lamports, base units) and exact decimals for the prices; never floating point. Pure: the fills come from the orders
 * settled on the network (M6), the prices from the market data (M4), and nothing PAPER is ever read.
 *
 * <ul>
 *   <li>a buy adds its quantity and its cost: the lamports spent plus the fee (also the fee paid by the main wallet in
 *       SIGNED, read from the order) plus the rent the bot wallet paid for an account the swap created (the RENT of the
 *       ledger), so the P&L reconciles with the ledger of the bot wallet;
 *   <li>a sell realizes the lamports received minus its fee minus the average cost of the part sold; that cost is
 *       rounded UP, so the realized result is never overstated (the cost left on the position may then be up to one
 *       lamport lower than the exact share), and is taken out of the position;
 *   <li>unrealized = value at the quote (lamports per base unit, rounded down) minus the remaining cost; an unknown price
 *       is unknown for the position and for the total, never zero.
 * </ul>
 */
public final class RealPnlCalculator {

    private RealPnlCalculator() {}

    /**
     * One settled order: a buy spends {@code lamports} for {@code baseUnits}; a sell receives them. {@code rentLamports}
     * is the rent the bot wallet paid for an account the buy created (0 for a sell or without one).
     */
    public record Fill(String mint, boolean buy, long lamports, long baseUnits, long feeLamports, long rentLamports) {

        public Fill(String mint, boolean buy, long lamports, long baseUnits, long feeLamports) {
            this(mint, buy, lamports, baseUnits, feeLamports, 0);
        }
    }

    public record PositionPnl(
            String mint,
            long baseUnits,
            long costLamports,
            Optional<Long> valueLamports,
            Optional<Long> unrealizedLamports) {}

    public record Result(
            long realizedLamports,
            Optional<Long> unrealizedLamports,
            long feesLamports,
            long rentLamports,
            List<PositionPnl> positions) {}

    /**
     * @param fills in the order of confirmation
     * @param prices lamports per base unit of each mint; empty or absent is unknown
     */
    public static Result of(List<Fill> fills, Map<String, Optional<BigDecimal>> prices) {
        Map<String, long[]> open = new LinkedHashMap<>();
        long realized = 0;
        long fees = 0;
        long rent = 0;
        for (Fill fill : fills) {
            fees = Math.addExact(fees, fill.feeLamports());
            rent = Math.addExact(rent, fill.rentLamports());
            long[] position = open.computeIfAbsent(fill.mint(), mint -> new long[2]);
            if (fill.buy()) {
                position[0] = Math.addExact(position[0], fill.baseUnits());
                long cost = Math.addExact(Math.addExact(fill.lamports(), fill.feeLamports()), fill.rentLamports());
                position[1] = Math.addExact(position[1], cost);
                continue;
            }
            long sold = Math.min(fill.baseUnits(), position[0]);
            long cost = sold == 0 ? 0 : ceilShare(position[1], sold, position[0]);
            realized = Math.addExact(realized, fill.lamports() - fill.feeLamports() - cost);
            position[0] -= sold;
            position[1] -= cost;
        }
        List<PositionPnl> positions = new ArrayList<>();
        boolean unknown = false;
        long unrealized = 0;
        for (Map.Entry<String, long[]> entry : open.entrySet()) {
            long units = entry.getValue()[0];
            long cost = entry.getValue()[1];
            if (units <= 0) {
                continue;
            }
            Optional<BigDecimal> price = prices.getOrDefault(entry.getKey(), Optional.empty());
            Optional<Long> value = price.map(p -> p.multiply(BigDecimal.valueOf(units))
                    .setScale(0, RoundingMode.DOWN)
                    .longValueExact());
            Optional<Long> gain = value.map(v -> v - cost);
            if (gain.isEmpty()) {
                unknown = true;
            } else {
                unrealized = Math.addExact(unrealized, gain.get());
            }
            positions.add(new PositionPnl(entry.getKey(), units, cost, value, gain));
        }
        return new Result(realized, unknown ? Optional.empty() : Optional.of(unrealized), fees, rent, positions);
    }

    /** ceil(cost x part / whole), exact. */
    private static long ceilShare(long cost, long part, long whole) {
        BigInteger[] division = BigInteger.valueOf(cost)
                .multiply(BigInteger.valueOf(part))
                .divideAndRemainder(BigInteger.valueOf(whole));
        return division[0].longValueExact() + (division[1].signum() > 0 ? 1 : 0);
    }
}
