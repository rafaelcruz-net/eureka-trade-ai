package com.memetrade.bots.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.application.DraftInput;
import com.memetrade.bots.domain.BotConfigValidator.RawRule;
import com.memetrade.bots.domain.BotConfigValidator.RawSelection;
import com.memetrade.bots.domain.BotConfigValidator.RawToken;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.worker.CycleResult;
import com.memetrade.bots.worker.CycleRunner;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import com.memetrade.marketdata.infra.SourceCaller;
import com.memetrade.marketdata.infra.TransactionUnitOfWork;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource.Scenario;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.NoWaitSleeper;
import com.memetrade.testsupport.U5Bots;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Build and Test, loop 2 (F8 and F9; NFR2.2, NFR2.3): the data path of the load scenario {@code u5} with the
 * frequent-signal scenario of the simulated source, end to end and with the injectable clock: the REAL collection of
 * U4 into the REAL store (one sample per token, source and field per 5-minute bucket, NFR2.4 of U4), the REAL C11
 * ({@link JdbcMarketDataQuery}) and scanner listing, and the REAL {@link CycleRunner} with U6 and U7 at
 * {@code slotStart + 60 s}, as {@code SlotDispatcher} runs it. Only C12 is scripted (LOW band), so the assessment never
 * hides the data reason under study. Two ACTIVE bots made like {@code scripts/load/load-test.mjs} makes them: one with
 * the rule {@code LIQUIDITY >= 1000000} of the script and one on a signal token chosen by hand.
 *
 * <p>For three consecutive slices, the test records, at the instant of each cycle, the age of the last closed bucket
 * {@code b_n} ({@code slotStart - 5 min}) and of the reference price of every signal token, then checks that no
 * decision is {@code STALE_DATA} while the simulated source is OK. The loadtest profile itself adds only the seeds
 * (off unless the script enables them) and the cycle stopwatch, none of them on this data path, so the test runs in
 * the worker context of {@link AbstractU5WorkerIT}. No network, synthetic mints only (fixed seed), a source of its own
 * that is disabled after the test so no other suite lists its tokens.
 */
class LoadtestFrequentSignalsFreshnessIT extends AbstractU5WorkerIT {

    /**
     * A multiple of 30 minutes (start of a phase of the saw), more than 24 hours after the market observations of the
     * other suites (so their tokens are never listed here) and before the slot of {@code U5ObservabilityIT}
     * (2027-06-01), which must stay the last finished cycle of the database.
     */
    private static final Instant START = Instant.parse("2027-03-01T11:00:00Z");

    private static final Duration COLLECTION = Duration.ofSeconds(30);
    private static final Duration SLICE = Duration.ofMinutes(5);
    private static final Duration CYCLE_DELAY = Duration.ofSeconds(60);
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final BigDecimal SIGNAL_LIQUIDITY = new BigDecimal("1000000");
    private static final long SEED = 20_261_107L;
    private static final int TOKENS = 20;

    @Autowired
    private MarketStore store;

    @Autowired
    private JdbcMarketDataQuery marketData;

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService callers = Executors.newCachedThreadPool();
    private RenamedSource source;
    private MarketCollector collector;
    private Instant nextCollection;

    @BeforeEach
    void ownSimulatedSource() {
        clock.set(START);
        String sourceId = "u5-fresh-" + UUID.randomUUID().toString().substring(0, 8);
        source = new RenamedSource(
                new SourceRef(sourceId, "Fonte simulada do teste de frescor da U5", true),
                new SimulatedMarketSource(
                        SimulatedMarketSource.SOURCE_A, SEED, TOKENS, clock, Scenario.FREQUENT_SIGNALS));
        store.registerSource(source.source(), COLLECTION);
        SourceCaller caller =
                new SourceCaller(callers, new NoWaitSleeper(), new Random(SEED), Duration.ofSeconds(5), (s, n) -> {});
        collector = new MarketCollector(
                store, caller, new TransactionUnitOfWork(new TransactionTemplate(transactionManager)), clock);
        nextCollection = START;
    }

    @AfterEach
    void disableTheSource() {
        jdbc.sql("update data_source set enabled = false where source_id = :s")
                .param("s", source.source().sourceId())
                .update();
        callers.shutdownNow();
    }

    @Test
    void theBotsOfTheFrequentSignalScenarioDecideOnFreshDataInConsecutiveSlices() {
        Instant firstSlot = START.plus(Duration.ofMinutes(45));
        collectUntil(firstSlot.plus(CYCLE_DELAY));
        List<TokenId> signalTokens = signalTokens();
        assertThat(signalTokens).as("signal tokens of the scenario").isNotEmpty();
        source.tokens().forEach(token -> assessments.band(token, RiskBand.LOW));
        Principal person = newPerson();
        List<UUID> botIds = List.of(
                botWith(person, new RawSelection(List.of(), List.of(new RawRule("LIQUIDITY", "GTE", "1000000")))),
                botWith(person, new RawSelection(List.of(raw(signalTokens.getFirst())), List.of())));

        List<String> ages = new ArrayList<>();
        List<CycleResult> results = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            Instant slot = firstSlot.plus(SLICE.multipliedBy(n));
            collectUntil(slot.plus(CYCLE_DELAY));
            signalTokens.forEach(token -> ages.add(ages(token, slot)));
            botIds.forEach(botId -> results.add(cycles.run(new BotRef(botId, TenantScope.of(person)), slot)));
        }

        assertThat(sourceStatus()).as("the simulated source is operating").isEqualTo("OK");
        assertThat(results).as("every cycle of the three slices").allMatch(r -> r.kind() == CycleResult.Kind.COMPLETED);
        List<String> reasons =
                jdbc.sql("""
                        select coalesce(no_trade_reason, action) from decision
                        where bot_id in (:bots) order by created_at, bot_id
                        """).param("bots", botIds).query(String.class).list();
        assertThat(reasons)
                .as("decisions of the 2 bots in 3 slices; ages at each cycle: %s", ages)
                .hasSize(6)
                .doesNotContain("STALE_DATA");
        assertThat(ages)
                .as("age of b_n and of the reference price at each cycle (at most 5 minutes)")
                .allMatch(line -> !line.contains("STALE"));
    }

    /** One collection every 30 s up to {@code cycleAt} (excluded), then the clock at {@code cycleAt}. */
    private void collectUntil(Instant cycleAt) {
        while (nextCollection.isBefore(cycleAt)) {
            clock.set(nextCollection);
            assertThat(collector.collect(source).status().name()).isEqualTo("OK");
            nextCollection = nextCollection.plus(COLLECTION);
        }
        clock.set(cycleAt);
    }

    /** The tokens of the saw: liquidity of at least 1.000.000 USDC (the rule of the load script selects them). */
    private List<TokenId> signalTokens() {
        return source.tokens().stream()
                .filter(token -> marketData.liquidity(token) instanceof MarketValue.Known known
                        && known.value().compareTo(SIGNAL_LIQUIDITY) >= 0)
                .toList();
    }

    /**
     * "mint slot b_n=<age> ref=<age>", with STALE after an age above 5 minutes, read as the cycle reads them now (BR4.1,
     * Passo 30): {@code b_n} by the end of the bucket, the reference price by its {@code observedAt}.
     */
    private String ages(TokenId token, Instant slot) {
        Instant now = clock.instant();
        Instant lastBucket = slot.minus(SLICE);
        PriceVolumeWindow window = marketData.window(token, Duration.ofMinutes(25));
        Optional<MarketValue> bucket = window.points().stream()
                .filter(point -> point.bucketStart().equals(lastBucket))
                .map(PriceVolumeWindow.Point::price)
                .findFirst();
        String bucketAge =
                bucket.orElse(null) instanceof MarketValue.Known ? age(lastBucket.plus(SLICE), now) : "UNKNOWN";
        return token.mint().substring(0, 6) + " " + slot + " b_n=" + bucketAge + " ref="
                + age(marketData.referencePrice(token), now);
    }

    private static String age(MarketValue value, Instant now) {
        if (!(value instanceof MarketValue.Known known)) {
            return "UNKNOWN";
        }
        return age(known.observedAt(), now);
    }

    private static String age(Instant since, Instant now) {
        Duration age = Duration.between(since, now);
        return age + (age.compareTo(MAX_AGE) > 0 ? " STALE" : "");
    }

    private UUID botWith(Principal person, RawSelection selection) {
        UUID botId = fixtures.draft(person);
        DraftInput first = U5Bots.step1(null);
        Map<String, String> parameters = new LinkedHashMap<>(first.parameters());
        fixtures.as(
                person,
                () -> commands.saveDraft(
                        person, botId, new DraftInput(1, parameters, selection, null, null), U5Bots.key()));
        fixtures.as(
                person,
                () -> commands.saveDraft(
                        person, botId, U5Bots.step2(U5Bots.defaultLimits(), "1000.000000"), U5Bots.key()));
        fixtures.as(
                person,
                () -> commands.saveDraft(person, botId, new DraftInput(3, null, null, null, null), U5Bots.key()));
        fixtures.as(person, () -> commands.start(person, botId, U5Bots.key()));
        return botId;
    }

    private static RawToken raw(TokenId token) {
        return new RawToken(token.chain(), token.mint());
    }

    private String sourceStatus() {
        return jdbc.sql("select status from data_source where source_id = :s")
                .param("s", source.source().sourceId())
                .query(String.class)
                .single();
    }

    /**
     * The simulated source of U4 in the frequent-signal scenario under a source id of this test: the same tokens,
     * values and observation instants, only the source of each value is this one.
     */
    private static final class RenamedSource implements MarketSourceAdapter {

        private final SourceRef ref;
        private final SimulatedMarketSource simulated;

        RenamedSource(SourceRef ref, SimulatedMarketSource simulated) {
            this.ref = ref;
            this.simulated = simulated;
        }

        @Override
        public SourceRef source() {
            return ref;
        }

        List<TokenId> tokens() {
            return simulated.fetch(new FetchRequest(START)).tokens().stream()
                    .map(TokenInfo::token)
                    .toList();
        }

        @Override
        public FetchResult fetch(FetchRequest request) {
            FetchResult fetched = simulated.fetch(request);
            List<Observation> renamed = fetched.observations().stream()
                    .map(o -> new Observation(o.token(), o.field(), renamed(o.value())))
                    .toList();
            return new FetchResult(fetched.tokens(), renamed);
        }

        private MarketValue renamed(MarketValue value) {
            return switch (value) {
                case MarketValue.Known known ->
                    new MarketValue.Known(known.value(), ref, known.observedAt(), known.stale());
                case MarketValue.Unknown unknown -> new MarketValue.Unknown(unknown.reason(), Optional.of(ref));
            };
        }
    }
}
