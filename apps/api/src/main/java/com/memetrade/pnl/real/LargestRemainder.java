package com.memetrade.pnl.real;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 261121-paper-real-unico U2 (BR4.1, BR4.2, BR4.8, decision of the founder Q2): the largest remainder method, the one
 * way any integer is shared. Each participant gets {@code floor(V x w_i / W)}; the units left go one by one to the
 * largest remainders {@code (V x w_i) mod W}, ties kept in the order of the participants (the tie-break of BR4.2). The
 * parts always sum exactly {@code V}. Exact arithmetic: {@code long} for the totals, {@link BigInteger} for the products.
 */
public final class LargestRemainder {

    private LargestRemainder() {}

    /**
     * @param total what is shared, at least 0
     * @param weights the weights, each at least 1, in the order of the tie-break
     * @return one part per weight, in the same order, summing {@code total}
     */
    public static long[] share(long total, long[] weights) {
        if (total < 0) {
            throw new IllegalArgumentException("a shared total is never negative");
        }
        if (weights.length == 0) {
            throw new IllegalArgumentException("a share needs at least one participant");
        }
        BigInteger sum = BigInteger.ZERO;
        for (long weight : weights) {
            if (weight < 1) {
                throw new IllegalArgumentException("a participant has a weight of at least 1 (BR4.8)");
            }
            sum = sum.add(BigInteger.valueOf(weight));
        }
        BigInteger value = BigInteger.valueOf(total);
        long[] parts = new long[weights.length];
        BigInteger[] remainders = new BigInteger[weights.length];
        long given = 0;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] division =
                    value.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(sum);
            parts[i] = division[0].longValueExact();
            remainders[i] = division[1];
            given = Math.addExact(given, parts[i]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            order.add(i);
        }
        // Stable: on an equal remainder the earlier participant keeps its place (BR4.2).
        order.sort(Comparator.comparing((Integer i) -> remainders[i]).reversed());
        long left = Math.subtractExact(total, given);
        for (int k = 0; k < left; k++) {
            int i = order.get(k);
            parts[i] = Math.addExact(parts[i], 1);
        }
        return parts;
    }
}
