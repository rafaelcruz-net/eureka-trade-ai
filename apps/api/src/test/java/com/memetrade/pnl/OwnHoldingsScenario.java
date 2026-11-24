package com.memetrade.pnl;

import com.memetrade.pnl.api.BotHolding;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U4 (test support of T12, T6 and the two paths of S4): a deterministic history of three REAL
 * bots of one person in four synthetic mints, written with {@link RealPnlSeed} the way the confirmations write it, and
 * the own quantity and cost of each bot computed by hand with the rules of the U2 (average cost; an own sale removes
 * the cost of the seller rounded up; a liquidation and a conditional are shared by quantity; a mint that reached zero
 * starts a new epoch):
 *
 * <ul>
 *   <li>M1: A buys 100 for 100,000,000; B buys 300 for 450,000,000; A sells 40 of its own: A 60 at 60,000,000, B 300 at
 *       450,000,000;
 *   <li>M2: C buys 50 for 10,000,000; a liquidation sells 20 (all of C): C 30 at 6,000,000;
 *   <li>M3: A buys 10 for 1,000; B buys 30 for 3,000; a conditional sells 20 shared 5 and 15: A 5 at 500, B 15 at 1,500;
 *   <li>M4: B buys 7 for 7 and sells the 7 (zero, a new epoch); then A buys 3 for 9: A 3 at 9.
 * </ul>
 */
public final class OwnHoldingsScenario {

    public final String m1 = SyntheticIds.mint();
    public final String m2 = SyntheticIds.mint();
    public final String m3 = SyntheticIds.mint();
    public final String m4 = SyntheticIds.mint();

    public final UUID a;
    public final UUID b;
    public final UUID c;

    public OwnHoldingsScenario(JdbcClient jdbc, UUID tenantId, UUID userId, UUID botWalletId, UUID a, UUID b, UUID c) {
        this.a = a;
        this.b = b;
        this.c = c;
        RealPnlSeed seed = new RealPnlSeed(
                jdbc, tenantId, userId, botWalletId, Instant.now().minusSeconds(86_400), 1_000);
        seed.buy(a, m1, 100_000_000L, 100_000_000L, 5_000);
        seed.buy(b, m1, 300_000_000L, 450_000_000L, 5_000);
        seed.sell(a, m1, 40_000_000L, 80_000_000L, 5_000);
        seed.buy(c, m2, 50, 10_000_000L, 5_000);
        seed.liquidation(m2, 20, 5_000_000L, 5_000);
        seed.buy(a, m3, 10, 1_000, 5_000);
        seed.buy(b, m3, 30, 3_000, 5_000);
        seed.conditional(m3, 20, 4_000, 5_000);
        seed.buy(b, m4, 7, 7, 5_000);
        seed.sell(b, m4, 7, 70, 5_000);
        seed.buy(a, m4, 3, 9, 5_000);
    }

    /** The own quantity and cost of {@code bot}, per mint, computed by hand. */
    public Map<String, long[]> expected(UUID bot) {
        Map<String, long[]> own = new LinkedHashMap<>();
        if (bot.equals(a)) {
            own.put(m1, new long[] {60_000_000L, 60_000_000L});
            own.put(m3, new long[] {5, 500});
            own.put(m4, new long[] {3, 9});
        } else if (bot.equals(b)) {
            own.put(m1, new long[] {300_000_000L, 450_000_000L});
            own.put(m3, new long[] {15, 1_500});
        } else if (bot.equals(c)) {
            own.put(m2, new long[] {30, 6_000_000L});
        }
        return own;
    }

    /** The own quantity and cost of an Available result, per mint (mints with an own quantity only). */
    public static Map<String, long[]> own(HoldingsResult result) {
        Map<String, long[]> own = new LinkedHashMap<>();
        for (BotHolding holding : ((HoldingsResult.Available) result).holdings()) {
            if (holding.ownTokenBaseUnits() > 0) {
                own.put(holding.mint(), new long[] {holding.ownTokenBaseUnits(), holding.ownCostLamports()});
            }
        }
        return own;
    }

    public List<UUID> bots() {
        return List.of(a, b, c);
    }
}
