package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.decision.domain.PositionInput;
import com.memetrade.pnl.RealPnlSeed;
import com.memetrade.pnl.real.ReconciliationResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 261121-paper-real-unico U4, Etapa 4, T9 (written before the code; BR1.7, AC6.1.2): a property over random histories
 * with three fixed seeds and 50 sequences each. A and B share the wallet; buys, own sales, liquidations and
 * conditionals of XYZ all end before the read. An independent model of the own quantity of A (own sales of the seller;
 * a sale without bot shared by quantity with the largest remainder, the older bot first on a tie) must be the quantity
 * the engine is given, and every SELL the cycle of A records stays within it: A never sells the part of B. The last
 * history of each seed is checked against the REAL ledger (exact reconciliation).
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityOtherBotsShareIT extends AbstractOwnQuantityIT {

    private static final int SEQUENCES = 50;

    @ParameterizedTest
    @ValueSource(longs = {20261124L, 7L, 424242L})
    void theCycleOfANeverSellsMoreThanItsOwnQuantity(long seedValue) {
        Random random = new Random(seedValue);
        Pair last = null;
        for (int sequence = 0; sequence < SEQUENCES; sequence++) {
            Pair pair = pair(false, false);
            long[] own = history(random, pair);
            OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

            cycle.run(pair.refA(), slot());

            List<PositionInput> positions = cycle.lastRequest().positions();
            long given = positions.isEmpty()
                    ? 0
                    : positions
                            .getFirst()
                            .quantity()
                            .movePointRight(6)
                            .toBigIntegerExact()
                            .longValueExact();
            assertThat(given).as("seed %d sequence %d", seedValue, sequence).isEqualTo(own[0]);
            for (long sold : sellDecisions(pair.a())) {
                assertThat(sold).isLessThanOrEqualTo(own[0]);
            }
            last = pair;
        }
        assertThat(reconciliation(last)).isEqualTo(ReconciliationResult.Status.MATCH);
    }

    /** A random history of XYZ; returns the own quantity of A and of B per the model. */
    private static long[] history(Random random, Pair pair) {
        RealPnlSeed seed = pair.seed();
        long a = 0;
        long b = 0;
        int steps = 3 + random.nextInt(4);
        List<String> done = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            int op = random.nextInt(5);
            if (op == 0 || a + b == 0) {
                long units = 1 + random.nextInt(1_000);
                seed.buy(pair.a(), pair.xyz(), units, units * 3, 5_000);
                a += units;
                done.add("buyA");
            } else if (op == 1) {
                long units = 1 + random.nextInt(1_000);
                seed.buy(pair.b(), pair.xyz(), units, units * 2, 5_000);
                b += units;
                done.add("buyB");
            } else if (op == 2 && a > 0) {
                long units = 1 + (long) random.nextInt((int) Math.min(a, Integer.MAX_VALUE));
                seed.sell(pair.a(), pair.xyz(), units, units * 4, 5_000);
                a -= units;
                done.add("sellA");
            } else {
                long total = a + b;
                long units = 1 + (long) random.nextInt((int) Math.min(total, Integer.MAX_VALUE));
                if (op == 3) {
                    seed.liquidation(pair.xyz(), units, units * 2, 5_000);
                } else {
                    seed.conditional(pair.xyz(), units, units * 2, 5_000);
                }
                long[] share = share(units, a, b);
                a -= share[0];
                b -= share[1];
                done.add(op == 3 ? "liquidation" : "conditional");
            }
        }
        return new long[] {a, b};
    }

    /** The largest remainder of {@code units} over the quantities of A and B; a tie goes to A (the older bot). */
    static long[] share(long units, long a, long b) {
        long total = a + b;
        BigDecimal exactA = BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(a));
        BigDecimal exactB = BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(b));
        long floorA = exactA.divideToIntegralValue(BigDecimal.valueOf(total)).longValueExact();
        long floorB = exactB.divideToIntegralValue(BigDecimal.valueOf(total)).longValueExact();
        long left = units - floorA - floorB;
        long remainderA = exactA.remainder(BigDecimal.valueOf(total)).longValueExact();
        long remainderB = exactB.remainder(BigDecimal.valueOf(total)).longValueExact();
        if (left > 0) {
            if (remainderA >= remainderB) {
                floorA++;
            } else {
                floorB++;
            }
        }
        return new long[] {floorA, floorB};
    }
}
