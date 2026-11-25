package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.pnl.api.BotHolding;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.pnl.application.RealBotHoldingsReader;
import com.memetrade.realexecution.OpenSellsFixture;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 261121-paper-real-unico U4, Etapa 11 (after the layer; NFR1.1, NFR1.2, NFR1.5, NFR1.6, NFR1.13, NFR2.17; alignment
 * A19; performance-design.md "Medida"): an INFORMATIVE measurement of the read of the own holdings, with NO gate on time
 * (team.md "Desempenho"; the comparison with the target of p99.9 at most 250 ms is of the Build and Test, the median of
 * three runs going to {@code docs/perf/}). It fails only for a read with an error or with a wrong quantity.
 *
 * <ul>
 *   <li>seed A (NFR1.5): 1 person, 1 bot wallet, 5 REAL bots and 10 mints, {@code u4.measure.days} days (730), 4 orders
 *       per bot per day, 5% own sales beyond the own quantity, 2% conditionals, 2% liquidations shared, the open sales
 *       of the measured bot (an AMBIGUOUS order; the unique index allows one order in progress per bot, so the other two
 *       are SENT orders of other bots) and 3 PENDING SELL decisions (one deferred), with the marks of zero the writes of
 *       the ledger record;
 *   <li>seed B (A19): the same without any zero in the whole history (S4 by the stream);
 *   <li>per seed, {@code u4.measure.warmups} warm-ups (20) and twice {@code u4.measure.reads} measured reads (300 in
 *       the CI; the official run passes {@code -Du4.measure.reads=10000 -Du4.measure.warmups=200
 *       -Du4.measure.cycles=100}), with the CLOCK OF THE SYSTEM (review R-01; the fixed clock of the context would leave
 *       the deadline inert): first
 *       with the budget of 250 ms, the p50, p99 and p99.9 (the sample size logged; below 1,000 reads the p99.9 is the
 *       maximum) and the rate of BUDGET_EXCEEDED the worker would see; then with a budget of 30 s, the time of the whole
 *       read and how many passed 250 ms (the comparison before and after a change); both paths of S4 give the same
 *       holdings; the own quantities of the bots add up to the wallet.
 *   <li>the cycle (skipped with {@code u4.measure.cycles=0}, the CI default): 10 warm-ups and
 *       {@code u4.measure.cycles} (100 in the official run) measured cycles with the flag on and off, the p95; the ratio
 *       of STALE_SNAPSHOT over the reads (0 expected).
 * </ul>
 */
@Tag("u4-own-quantity-cycle")
class RealBotHoldingsMeasurementIT extends AbstractOwnQuantityIT {

    private static final Logger log = LoggerFactory.getLogger(RealBotHoldingsMeasurementIT.class);
    private static final Duration BUDGET = Duration.ofMillis(250);
    private static final int BOTS = 5;
    private static final int MINTS = 10;

    private final int days = Integer.getInteger("u4.measure.days", 730);
    private final int reads = Integer.getInteger("u4.measure.reads", 300);
    private final int cycles = Integer.getInteger("u4.measure.cycles", 0);
    private final int warmups = Integer.getInteger("u4.measure.warmups", 20);

    @ParameterizedTest
    @ValueSource(strings = {"A", "B"})
    void theReadOfTheOwnHoldingsIsMeasured(String seed) {
        Setup setup = realBot("AUTO_FULL");
        List<UUID> bots = new ArrayList<>(List.of(setup.botId()));
        for (int i = 1; i < BOTS; i++) {
            bots.add(realBotRow(setup.tenantId(), setup.userId()));
        }
        List<String> mints = new ArrayList<>();
        for (int i = 0; i < MINTS; i++) {
            mints.add(token("0.0001", 6, 5));
        }
        History history = new History(setup, bots, mints, "A".equals(seed));
        history.write();
        ledger.markEpochs(setup.tenantId(), setup.botWalletId());
        UUID measured = bots.getFirst();
        openSales(setup, bots, mints);

        // Review R-01: the clock of the system, never the fixed clock of the test context, so the deadline acts as in
        // production (the statement timeout of what remains, the checks, the check after the commit).
        RealBotHoldingsReader reader = HoldingsReaders.withClock(context, Clock.systemUTC());
        checkTheHoldings(setup, bots, mints, history);
        for (int i = 0; i < warmups; i++) {
            reader.holdings(setup.tenantId(), setup.userId(), measured, BUDGET);
        }
        // 1. With the budget of production (250 ms): the rate of BUDGET_EXCEEDED the worker would see.
        Sample budgeted = sample(reader, setup, measured, BUDGET);
        log.info(
                "u4-measure seed={} orders={} mode=budget250 n={} p50={}ms p99={}ms p99.9={}ms{} budgetExceeded={}"
                        + " ({}%) readFailed={} (target: p99.9 <= 250 ms)",
                seed,
                history.orders,
                reads,
                rank(budgeted.nanos, 0.50),
                rank(budgeted.nanos, 0.99),
                rank(budgeted.nanos, 0.999),
                reads < 1_000 ? " (n < 1000: the p99.9 is the maximum)" : "",
                budgeted.budgetExceeded,
                percent(budgeted.budgetExceeded, reads),
                budgeted.readFailed);
        // 2. Without a budget that bites (30 s): the time of the whole read, for the comparison before and after.
        Sample free = sample(reader, setup, measured, Duration.ofSeconds(30));
        long over = Arrays.stream(free.nanos).filter(n -> n > BUDGET.toNanos()).count();
        log.info(
                "u4-measure seed={} orders={} mode=unbounded n={} p50={}ms p99={}ms p99.9={}ms over250={} ({}%)"
                        + " unavailable={}",
                seed,
                history.orders,
                reads,
                rank(free.nanos, 0.50),
                rank(free.nanos, 0.99),
                rank(free.nanos, 0.999),
                over,
                percent(over, reads),
                free.budgetExceeded + free.readFailed);
        if (cycles > 0) {
            measureTheCycle(seed, setup);
        }
    }

    /** {@code reads} timed reads with {@code budget}, sorted, with the count of each failure. */
    private Sample sample(RealBotHoldingsReader reader, Setup setup, UUID measured, Duration budget) {
        long[] nanos = new long[reads];
        int exceeded = 0;
        int failed = 0;
        for (int i = 0; i < reads; i++) {
            long started = System.nanoTime();
            HoldingsResult result = reader.holdings(setup.tenantId(), setup.userId(), measured, budget);
            nanos[i] = System.nanoTime() - started;
            if (result instanceof HoldingsResult.Unavailable unavailable) {
                if (unavailable.reason() == UnavailableReason.BUDGET_EXCEEDED) {
                    exceeded++;
                } else {
                    failed++;
                }
            }
        }
        Arrays.sort(nanos);
        return new Sample(nanos, exceeded, failed);
    }

    private record Sample(long[] nanos, int budgetExceeded, int readFailed) {}

    /** A percentage with one decimal, in exact arithmetic (for the log only). */
    private static String percent(long part, long whole) {
        return java.math.BigDecimal.valueOf(part * 100L)
                .divide(java.math.BigDecimal.valueOf(whole), 1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** Both paths of S4 give the same holdings, and the own quantities of the bots add up to the wallet. */
    private void checkTheHoldings(Setup setup, List<UUID> bots, List<String> mints, History history) {
        RealBotHoldingsReader byId = HoldingsReaders.of(context, Integer.MAX_VALUE);
        RealBotHoldingsReader byStream = HoldingsReaders.of(context, 0);
        Duration generous = Duration.ofSeconds(30);
        Map<String, Long> own = new HashMap<>();
        for (UUID bot : bots) {
            HoldingsResult first = byId.holdings(setup.tenantId(), setup.userId(), bot, generous);
            HoldingsResult second = byStream.holdings(setup.tenantId(), setup.userId(), bot, generous);
            assertThat(first).as("bot %s", bot).isInstanceOf(HoldingsResult.Available.class);
            assertThat(((HoldingsResult.Available) second).holdings())
                    .isEqualTo(((HoldingsResult.Available) first).holdings());
            for (BotHolding holding : ((HoldingsResult.Available) first).holdings()) {
                own.merge(holding.mint(), holding.ownTokenBaseUnits(), Long::sum);
            }
        }
        RealLedgerView view =
                transactions.execute(status -> ledger.view(setup.tenantId(), setup.botWalletId(), Optional.empty()));
        for (RealPosition position : view.positions()) {
            if (mints.contains(position.mint())) {
                assertThat(own.getOrDefault(position.mint(), 0L))
                        .as("the bots of the wallet own all of %s", position.mint())
                        .isEqualTo(position.tokenBaseUnits());
            }
        }
        for (String mint : mints) {
            assertThat(history.wallet.getOrDefault(mint, 0L))
                    .isEqualTo(view.positions().stream()
                            .filter(p -> p.mint().equals(mint))
                            .mapToLong(RealPosition::tokenBaseUnits)
                            .sum());
        }
    }

    /** The open sales of the measured bot (one AMBIGUOUS order and 3 PENDING SELL decisions, one deferred). */
    private void openSales(Setup setup, List<UUID> bots, List<String> mints) {
        OpenSellsFixture open = new OpenSellsFixture(jdbc);
        open.order(setup.tenantId(), setup.userId(), bots.get(0), "SELL", mints.get(0), 1, "AMBIGUOUS");
        open.order(setup.tenantId(), setup.userId(), bots.get(1), "SELL", mints.get(1), 1, "SENT");
        open.order(setup.tenantId(), setup.userId(), bots.get(2), "SELL", mints.get(2), 1, "SENT");
        open.pendingSell(setup.tenantId(), setup.userId(), bots.get(0), mints.get(3), 1);
        open.pendingSell(setup.tenantId(), setup.userId(), bots.get(0), mints.get(4), 1);
        open.decision(
                setup.tenantId(),
                setup.userId(),
                bots.get(0),
                "SELL",
                mints.get(5),
                1,
                "PENDING",
                Instant.now().plus(Duration.ofMinutes(10)));
    }

    /** The cycle of the measured bot with the flag on and off: the p95, and the ratio of STALE_SNAPSHOT. */
    private void measureTheCycle(String seed, Setup setup) {
        for (boolean on : List.of(true, false)) {
            OwnQuantityCycle cycle = new OwnQuantityCycle(
                            context, on, HoldingsReaders.withClock(context, Clock.systemUTC()))
                    .decidesNothing();
            com.memetrade.bots.domain.BotRef ref = new com.memetrade.bots.domain.BotRef(
                    setup.botId(), new com.memetrade.bots.domain.TenantScope(setup.tenantId(), setup.userId()));
            Instant slot = slot();
            long[] nanos = new long[cycles];
            for (int i = 0; i < 10 + cycles; i++) {
                long started = System.nanoTime();
                cycle.run(ref, slot.plus(Duration.ofMinutes(5L * (i + 1 + (on ? 0 : 1_000)))));
                if (i >= 10) {
                    nanos[i - 10] = System.nanoTime() - started;
                }
            }
            Arrays.sort(nanos);
            double readsCount =
                    cycle.meters.get("real_own_holdings_reads").counter().count();
            double stale = cycle.meters
                    .get("real_cycle_sell_capped")
                    .tag("outcome", "STALE_SNAPSHOT")
                    .counter()
                    .count();
            log.info(
                    "u4-measure seed={} cycle flag={} cycles={} p95={}ms staleRatio={} (expected 0)",
                    seed,
                    on ? "on" : "off",
                    cycles,
                    rank(nanos, 0.95),
                    readsCount == 0 ? "-" : String.valueOf((long) stale) + "/" + (long) readsCount);
        }
    }

    /** The nearest rank of {@code p} in sorted nanoseconds, in milliseconds. */
    private static long rank(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return Duration.ofNanos(sorted[Math.max(0, index)]).toMillis();
    }

    /**
     * A deterministic history written in bulk the way the confirmations write it (reference {@code order:<id>}, keys
     * {@code <id>:<KIND>}, the signature on every row, its slot in {@code real_entry_slot}); the quantities of the
     * wallet per mint are tracked so a sale never passes the wallet.
     */
    private final class History {

        private final Setup setup;
        private final List<UUID> bots;
        private final List<String> mints;
        private final boolean zeroes;
        private final Random random = new Random(20261124L);
        private final Map<String, Long> wallet = new HashMap<>();
        private final Map<String, Map<UUID, Long>> own = new HashMap<>();
        private final List<MapSqlParameterSource> orderRows = new ArrayList<>();
        private final List<MapSqlParameterSource> entryRows = new ArrayList<>();
        private final List<MapSqlParameterSource> slotRows = new ArrayList<>();
        private long slot = 1_000_000;
        private Instant at;
        private int orders;

        History(Setup setup, List<UUID> bots, List<String> mints, boolean zeroes) {
            this.setup = setup;
            this.bots = bots;
            this.mints = mints;
            this.zeroes = zeroes;
            this.at = Instant.now().minus(Duration.ofDays(days + 1));
        }

        void write() {
            for (int day = 0; day < days; day++) {
                for (int b = 0; b < bots.size(); b++) {
                    for (int k = 0; k < 4; k++) {
                        step(bots.get(b), mints.get((b * 2 + k + day) % mints.size()));
                    }
                }
                if (zeroes && day % 30 == 29) {
                    zero(mints.get((day / 30) % mints.size()));
                }
                at = at.plus(Duration.ofDays(1));
            }
            NamedParameterJdbcTemplate named =
                    new NamedParameterJdbcTemplate(context.getBean(javax.sql.DataSource.class));
            named.batchUpdate("""
                            insert into real_order (real_order_id, tenant_id, user_id, bot_wallet_id, bot_id,
                              liquidation_id, origin, idempotency_key, side, mint, input_lamports, input_base_units,
                              operation_mode, signing_mode, max_slippage_bps, reason, state, tx_signature,
                              out_base_units, fee_lamports, fee_payer, created_at, updated_at)
                            values (:id, :t, :u, :w, :b, :l, :origin, :key, :side, :mint, :lamports, :units,
                              'AUTO_FULL', 'AUTO_FULL', 100, 'medida', 'CONFIRMED', :sig, :out, 5000, 'BOT_WALLET',
                              :at, :at)""", orderRows.toArray(MapSqlParameterSource[]::new));
            named.batchUpdate("""
                            insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                              mint, token_base_units, tx_signature, reference, idempotency_key, created_at)
                            values (gen_random_uuid(), :t, :w, :kind, :lamports, :mint, :units, :sig, :ref, :key,
                              :at)""", entryRows.toArray(MapSqlParameterSource[]::new));
            named.batchUpdate(
                    "insert into real_entry_slot (tx_signature, slot, recorded_at) values (:sig, :slot, :at)",
                    slotRows.toArray(MapSqlParameterSource[]::new));
        }

        private void step(UUID bot, String mint) {
            long mine = own(mint).getOrDefault(bot, 0L);
            int dice = random.nextInt(100);
            long held = wallet.getOrDefault(mint, 0L);
            if (dice < 2 && held > 1) {
                fill(null, true, "SELL", mint, 1 + random.nextInt((int) Math.min(held - 1, 1_000)), false);
            } else if (dice < 4 && held > 1) {
                fill(null, false, "SELL", mint, 1 + random.nextInt((int) Math.min(held - 1, 1_000)), true);
            } else if (dice < 9 && mine > 0 && held > mine + 1) {
                fill(bot, false, "SELL", mint, mine + 1, false);
            } else if (mine > 0 && random.nextBoolean()) {
                fill(bot, false, "SELL", mint, 1 + random.nextInt((int) Math.min(mine, 1_000)), false);
            } else {
                fill(bot, false, "BUY", mint, 1 + random.nextInt(1_000), false);
            }
        }

        /** Every bot sells all it owns of {@code mint}: the wallet reaches zero (a mark of zero). */
        private void zero(String mint) {
            for (UUID bot : bots) {
                long mine = own(mint).getOrDefault(bot, 0L);
                if (mine > 0) {
                    fill(bot, false, "SELL", mint, mine, false);
                }
            }
        }

        private void fill(UUID bot, boolean liquidation, String side, String mint, long units, boolean conditional) {
            UUID id = UUID.randomUUID();
            String sig = SyntheticIds.signature();
            String reference = conditional ? "conditional:" + id : "order:" + id;
            boolean buy = "BUY".equals(side);
            long lamports = units * 3;
            if (!conditional) {
                orderRows.add(new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("t", setup.tenantId())
                        .addValue("u", setup.userId())
                        .addValue("w", setup.botWalletId())
                        .addValue("b", bot)
                        .addValue("l", liquidation ? UUID.randomUUID() : null)
                        .addValue("origin", liquidation ? "USER_LIQUIDATION" : "BOT")
                        .addValue("key", "measure:" + id)
                        .addValue("side", side)
                        .addValue("mint", mint)
                        .addValue("lamports", buy ? lamports : null)
                        .addValue("units", buy ? null : units)
                        .addValue("sig", sig)
                        .addValue("out", buy ? units : lamports)
                        .addValue("at", Timestamp.from(at)));
                orders++;
            }
            entryRows.add(new MapSqlParameterSource()
                    .addValue("t", setup.tenantId())
                    .addValue("w", setup.botWalletId())
                    .addValue("kind", buy ? "SWAP_FILL_IN" : "SWAP_FILL_OUT")
                    .addValue("lamports", buy ? -lamports : lamports)
                    .addValue("mint", mint)
                    .addValue("units", units)
                    .addValue("sig", sig)
                    .addValue("ref", reference)
                    .addValue(
                            "key",
                            (conditional ? "conditional:" + id : id.toString()) + ":"
                                    + (buy ? "SWAP_FILL_IN" : "SWAP_FILL_OUT"))
                    .addValue("at", Timestamp.from(at)));
            slotRows.add(new MapSqlParameterSource()
                    .addValue("sig", sig)
                    .addValue("slot", slot++)
                    .addValue("at", Timestamp.from(at)));
            track(bot, liquidation || conditional, buy, mint, units);
        }

        /** The model of the quantities: the wallet, and the own of each bot (a sale without bot is not modelled). */
        private void track(UUID bot, boolean withoutBot, boolean buy, String mint, long units) {
            wallet.merge(mint, buy ? units : -units, Long::sum);
            if (withoutBot || bot == null) {
                // Shared among the holders by the U2; only the wallet total is modelled exactly.
                rescale(mint);
                return;
            }
            own(mint).merge(bot, buy ? units : -units, Long::sum);
            if (!buy && own(mint).get(bot) < 0) {
                own(mint).put(bot, 0L);
                rescale(mint);
            }
        }

        /** Keeps the model of the own quantities within the wallet after a shared or excess sale (an upper bound). */
        private void rescale(String mint) {
            long total = own(mint).values().stream().mapToLong(Long::longValue).sum();
            long held = wallet.getOrDefault(mint, 0L);
            if (total > held) {
                long excess = total - held;
                for (Map.Entry<UUID, Long> entry : own(mint).entrySet()) {
                    long cut = Math.min(entry.getValue(), excess);
                    entry.setValue(entry.getValue() - cut);
                    excess -= cut;
                }
            }
        }

        private Map<UUID, Long> own(String mint) {
            return own.computeIfAbsent(mint, m -> new HashMap<>());
        }
    }
}
