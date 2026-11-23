package com.memetrade.riskcontrol.real;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.api.RealWalletCost;
import com.memetrade.pnl.api.WalletCost;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.testsupport.SyntheticIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 14 (NFR1.4, NFR1.7, NFR1.8, NFR1.26; performance-design.md "Medição"): an
 * INFORMATIVE measurement, with no gate on the numbers (team.md "Desempenho"; the comparison with the targets is of the
 * Build and Test). 21 wallets with 10 open positions each, 2 of them without a price, and the reference density of
 * NFR1.14 (5 REAL bots, 4 orders per bot per day, 2 or 3 entries per order, {@code u2.measure.days} days, 730 by
 * default); 4 reads of cost per second (path GATE) while entries are written concurrently at the reference density;
 * 10 warm-ups and 100 measures; the p95 by the nearest rank and the rate of INCONSISTENT_READ are logged. The buy with a
 * position without price is measured too, and reads no attribution. The test fails only when an evaluation fails.
 *
 * <p>Etapa 14b (decision of the founder of 2026-11-22): the wallets carry the marks of zero the writers would have
 * written along their history ({@code markEpochs} after the seed), so the view reads each mint from its latest zero. The
 * log adds the reads that fell back to the whole history, what a writer pays for {@code markEpochs}, and the documented
 * worst case: 5 more wallets WITHOUT marks (each mint read from its first fill), the time of their first views and the
 * p95 of their buys.
 */
@Tag("u2-real-bot-pnl")
class RealAttributionMeasurementIT extends AbstractRealExecutionIT {

    private static final Logger log = LoggerFactory.getLogger(RealAttributionMeasurementIT.class);

    private static final int WALLETS = 21;
    private static final int POSITIONS = 10;
    private static final int UNPRICED = 2;
    private static final int PER_SECOND = 4;
    private static final int WARM_UP = 10;
    private static final int MEASURED = 100;
    private static final int DAYS = Integer.getInteger("u2.measure.days", 730);

    /**
     * Etapa 14a: a database of its own in the shared container (created, bootstrapped and migrated like the shared one),
     * so the million rows of this measurement never slow the other integration tests of the same JVM.
     */
    private static final String DATABASE = "u2measure_";

    @org.springframework.test.context.DynamicPropertySource
    static void ownDatabase(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> com.memetrade.testsupport.OwnTestDatabase.url(DATABASE));
    }

    @Autowired
    private RealWalletCost walletCost;

    @Autowired
    private RealRiskGate gate;

    @Autowired
    private MeterRegistry registry;

    private record Seeded(Setup setup, List<String> mints) {}

    @Test
    void theReadOfCostOfTheRoundsAndTheBuyWithAPositionWithoutPriceAreMeasured() throws Exception {
        List<Seeded> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            Seeded seeded = seed();
            // Etapa 14b: the marks of zero the writers would have written along this history (one read of it).
            transactions.executeWithoutResult(status ->
                    ledger.markEpochs(seeded.setup().tenantId(), seeded.setup().botWalletId()));
            wallets.add(seeded);
        }
        double fallbacksBefore = fallbacks();
        AtomicBoolean writing = new AtomicBoolean(true);
        Thread writer = Thread.ofVirtual().start(() -> concurrentEntries(wallets, writing));
        List<Duration> times = new ArrayList<>();
        AtomicInteger inconsistent = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        ScheduledExecutorService pacer = Executors.newScheduledThreadPool(PER_SECOND);
        try {
            List<java.util.concurrent.Future<?>> reads = new ArrayList<>();
            for (int i = 0; i < WARM_UP + MEASURED; i++) {
                Seeded wallet = wallets.get(i % WALLETS);
                boolean measured = i >= WARM_UP;
                reads.add(pacer.schedule(
                        () -> {
                            long[] elapsed = {0};
                            WalletCost cost = transactions.execute(status -> {
                                com.memetrade.ledger.api.RealLedgerView rows = ledger.view(
                                        wallet.setup().tenantId(),
                                        wallet.setup().botWalletId(),
                                        Optional.empty());
                                // NFR1.4 measures the read of cost itself, after the view of the round.
                                long started = System.nanoTime();
                                WalletCost read = walletCost.costOf(
                                        wallet.setup().tenantId(),
                                        wallet.setup().userId(),
                                        wallet.setup().botWalletId(),
                                        CostPath.GATE,
                                        rows);
                                elapsed[0] = System.nanoTime() - started;
                                return read;
                            });
                            Duration took = Duration.ofNanos(elapsed[0]);
                            if (measured) {
                                synchronized (times) {
                                    times.add(took);
                                }
                                if (cost instanceof WalletCost.Unknown unknown) {
                                    if (unknown.reason() == RealCostUnknownReason.INCONSISTENT_READ) {
                                        inconsistent.incrementAndGet();
                                    } else if (unknown.reason() == RealCostUnknownReason.TIMEOUT) {
                                        timeouts.incrementAndGet();
                                    }
                                }
                            }
                        },
                        i * (1000L / PER_SECOND),
                        TimeUnit.MILLISECONDS));
            }
            for (java.util.concurrent.Future<?> read : reads) {
                read.get(10, TimeUnit.MINUTES);
            }
        } finally {
            pacer.shutdownNow();
            writing.set(false);
            writer.join(Duration.ofSeconds(30));
        }

        double gateReadsBefore = gateReads();
        List<Duration> buys = buys(wallets);
        double fallbacksAfter = fallbacks();

        // Etapa 14b: what a writer pays for the marks after its entries (a read restricted by the marks).
        List<Duration> marking = new ArrayList<>();
        for (Seeded wallet : wallets) {
            long started = System.nanoTime();
            transactions.executeWithoutResult(status ->
                    ledger.markEpochs(wallet.setup().tenantId(), wallet.setup().botWalletId()));
            marking.add(Duration.ofNanos(System.nanoTime() - started));
        }

        // Etapa 14b, the documented worst case: wallets whose mints have no mark (a history written before the marks
        // existed, or positions held since their first fill) are read from the first fill of each mint.
        List<Seeded> unmarked = new ArrayList<>();
        for (int i = 0; i < UNMARKED; i++) {
            unmarked.add(seed());
        }
        // The planner sees the rows just seeded (a history of two years is never minutes old in production).
        com.memetrade.testsupport.OwnTestDatabase.analyze(DATABASE);
        List<Duration> worstViews = new ArrayList<>();
        for (Seeded wallet : unmarked) {
            long started = System.nanoTime();
            transactions.execute(status ->
                    ledger.view(wallet.setup().tenantId(), wallet.setup().botWalletId(), Optional.empty()));
            worstViews.add(Duration.ofNanos(System.nanoTime() - started));
        }
        List<Duration> worstBuys = buys(unmarked);

        log.info(
                "U2 informative measurement (no gate): wallets={}, positionsPerWallet={}, unpricedPerWallet={},"
                        + " days={}, readsPerSecond={}, measured={}, gateCostReadP95Ms={}, timeouts={},"
                        + " inconsistentReads={}, inconsistentRate={}, buyWithUnpricedP95Ms={}, targetMs=250,"
                        + " epochFallbacks={}, markEpochsP95Ms={}, unmarkedWallets={}, viewUnmarkedFirstReadsMs={},"
                        + " buyWithUnpricedUnmarkedP95Ms={}",
                WALLETS,
                POSITIONS,
                UNPRICED,
                DAYS,
                PER_SECOND,
                times.size(),
                p95(times).toMillis(),
                timeouts.get(),
                inconsistent.get(),
                inconsistent.get() + "/" + times.size(),
                p95(buys).toMillis(),
                (long) (fallbacksAfter - fallbacksBefore),
                p95(marking).toMillis(),
                UNMARKED,
                worstViews.stream().map(Duration::toMillis).toList(),
                p95(worstBuys).toMillis());
        assertThat(times).hasSize(MEASURED);
        assertThat(gateReads()).as("the buy reads no attribution").isEqualTo(gateReadsBefore);
    }

    /** Etapa 14b: the wallets of the worst case, seeded without the marks. */
    private static final int UNMARKED = 5;

    /** One buy evaluated by the barrier per wallet, each in its own transaction. */
    private List<Duration> buys(List<Seeded> wallets) {
        List<Duration> buys = new ArrayList<>();
        for (Seeded wallet : wallets) {
            long started = System.nanoTime();
            GateDecision decision = transactions.execute(status -> gate.evaluate(OrderIntent.botBuy(
                    wallet.setup().tenantId(),
                    wallet.setup().userId(),
                    wallet.setup().botId(),
                    UUID.randomUUID(),
                    wallet.setup().mint(),
                    50_000_000L)));
            buys.add(Duration.ofNanos(System.nanoTime() - started));
            assertThat(decision.evaluationId()).isNotNull();
        }
        return buys;
    }

    private double fallbacks() {
        return registry.find("ledger.real.epoch.fallbacks").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    /** One wallet: the history of NFR1.14 and 10 open positions, the last 2 of them without a price. */
    private Seeded seed() {
        Setup setup = realBot("AUTO_FULL");
        List<String> mints = new ArrayList<>();
        for (int p = 0; p < POSITIONS; p++) {
            mints.add(p < POSITIONS - UNPRICED ? token("0.0001", 6, 5) : SyntheticIds.mint());
        }
        jdbc.sql("""
                        with orders as (
                          insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                            side, mint, input_lamports, input_base_units, operation_mode, signing_mode,
                            max_slippage_bps, reason, state, tx_signature, out_base_units, fee_lamports, fee_payer,
                            created_at, updated_at)
                          select gen_random_uuid(), :t, :u, :b, 'BOT', 'measure:' || :w || ':' || n,
                            case when n > :orders or n % 2 = 0 then 'BUY' else 'SELL' end,
                            case when n > :orders then (:mints)[n - :orders] else (:mints)[1 + (n / 2) % 10] end,
                            case when n > :orders or n % 2 = 0 then 1000000 end,
                            case when n <= :orders and n % 2 = 1 then 1000 end, 'AUTO_FULL',
                            'AUTO_FULL', 100, 'medida', 'CONFIRMED', 'measure-' || :w || '-' || n, 1, 5000,
                            'BOT_WALLET', cast(:start as timestamptz) + (n - 10) * interval '72 minutes',
                            cast(:start as timestamptz) + (n - 10) * interval '72 minutes'
                          from generate_series(1, :orders + 10) n
                          returning real_order_id, side, mint, tx_signature, created_at
                        )
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, mint,
                          token_base_units, tx_signature, reference, idempotency_key, created_at)
                        select gen_random_uuid(), :t, :w, k.kind,
                          case k.kind when 'SWAP_FILL_IN' then -1000000 when 'SWAP_FILL_OUT' then 600000
                            when 'NETWORK_FEE' then -5000 else -2039280 end,
                          case when k.kind like 'SWAP_FILL_%' then o.mint end,
                          case when k.kind like 'SWAP_FILL_%' then 1000 end,
                          o.tx_signature, 'order:' || o.real_order_id, o.real_order_id || ':' || k.kind, o.created_at
                        from orders o
                        cross join lateral (values
                          (case when o.side = 'BUY' then 'SWAP_FILL_IN' else 'SWAP_FILL_OUT' end),
                          ('NETWORK_FEE'),
                          (case when o.side = 'BUY' then 'RENT' end)) k(kind)
                        where k.kind is not null""")
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .param("b", setup.botId())
                .param("w", setup.botWalletId())
                .param("mints", mints.toArray(String[]::new))
                .param("orders", DAYS * 20)
                .param("start", Timestamp.from(clock.instant().minus(Duration.ofDays(DAYS))))
                .update();
        jdbc.sql("""
                        insert into real_entry_slot (tx_signature, slot, recorded_at)
                        select 'measure-' || :w || '-' || n, 1000000 + n * 150, now()
                        from generate_series(:orders / 2, :orders) n""").param("w", setup.botWalletId()).param("orders", DAYS * 20).update();
        return new Seeded(setup, mints);
    }

    /** Entries written meanwhile at the reference density (about 120 per wallet per day), compressed in time. */
    private void concurrentEntries(List<Seeded> wallets, AtomicBoolean writing) {
        int n = 0;
        while (writing.get()) {
            Seeded wallet = wallets.get(n++ % wallets.size());
            String key = "measure-dep:" + UUID.randomUUID();
            com.memetrade.testsupport.OwnTestDatabase.connect(DATABASE, APP)
                    .sql("""
                            insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                              reference, idempotency_key, created_at)
                            values (gen_random_uuid(), :t, :w, 'DEPOSIT', 1, :key, :key, now())""")
                    .param("t", wallet.setup().tenantId())
                    .param("w", wallet.setup().botWalletId())
                    .param("key", key)
                    .update();
            try {
                // The reference density: about 120 entries per wallet per day, so one entry every 86.400 s / 120 /
                // the number of wallets (about 34 s for 21 wallets), checked every 100 ms to stop on time.
                long pause = 86_400_000L / 120 / wallets.size();
                for (long slept = 0; slept < pause && writing.get(); slept += 100) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private double gateReads() {
        return registry.find("pnl.real.attribution.read").tag("path", "GATE").timers().stream()
                .mapToDouble(timer -> timer.count())
                .sum();
    }

    private static Duration p95(List<Duration> measured) {
        List<Duration> sorted =
                measured.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.isEmpty() ? Duration.ZERO : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
    }
}
