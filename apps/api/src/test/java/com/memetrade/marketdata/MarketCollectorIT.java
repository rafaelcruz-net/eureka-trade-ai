package com.memetrade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.CollectionOutcome;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.JdbcMarketStore;
import com.memetrade.marketdata.infra.MarketCollectorJob;
import com.memetrade.marketdata.infra.MarketDataProperties;
import com.memetrade.marketdata.infra.MarketSourceRegistry;
import com.memetrade.marketdata.infra.SourceCaller;
import com.memetrade.marketdata.infra.TransactionUnitOfWork;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.FixtureMarketSource;
import com.memetrade.testsupport.NoWaitSleeper;
import com.memetrade.testsupport.ScriptedMarketSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F1 against PostgreSQL with the application migrations (Step 5): states and transitions, time limit and new attempts,
 * all or nothing, one sample per bucket, the global mark, restart and the lock. Each test uses its own source id.
 */
@Tag("u4")
class MarketCollectorIT extends AbstractPostgresIT {

    @Autowired
    private MarketStore store;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private NamedParameterJdbcTemplate named;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private MarketCollectorJob job;

    @Autowired
    private MarketDataProperties properties;

    private final NoWaitSleeper sleeper = new NoWaitSleeper();
    private String sourceId;
    private ScriptedMarketSource source;

    @BeforeEach
    void ownSource() {
        sourceId = "it-" + UUID.randomUUID().toString().substring(0, 8);
        source = new ScriptedMarketSource(FixtureMarketSource.load("sim-a").as(sourceId));
        store.registerSource(source.source(), Duration.ofSeconds(30));
    }

    @Test
    void aNewSourceIsPendingAndItsFirstCollectionMakesItOkWithEveryField() {
        assertThat(status()).isEqualTo("PENDING");

        CollectionOutcome outcome = collector(store).collect(source);

        assertThat(outcome.previous()).isEqualTo(State.PENDING);
        assertThat(status()).isEqualTo("OK");
        assertThat(count("select count(*) from market_observation where source_id = :s"))
                .isEqualTo(4 * 5);
        assertThat(count("select count(*) from observation_sample where source_id = :s"))
                .isEqualTo(4 * 4);
    }

    @Test
    void aTimeOutOnEveryAttemptMakesTheSourceFailedWithTimeoutAndKeepsTheValues() {
        collector(store).collect(source);
        int before = count("select count(*) from market_observation where source_id = :s");
        source.thenDelay(Duration.ofMillis(400))
                .thenDelay(Duration.ofMillis(400))
                .thenDelay(Duration.ofMillis(400));
        clock.advance(Duration.ofSeconds(30));

        CollectionOutcome outcome = collector(store, Duration.ofMillis(100)).collect(source);

        assertThat(outcome.status()).isEqualTo(State.FAILED);
        assertThat(outcome.retries()).isEqualTo(2);
        assertThat(jdbc.sql("select failure_category from data_source where source_id = :s")
                        .param("s", sourceId)
                        .query(String.class)
                        .single())
                .isEqualTo("TIMEOUT");
        assertThat(count("select count(*) from market_observation where source_id = :s"))
                .isEqualTo(before);
        assertThat(sleeper.waits()).hasSize(2);
    }

    @Test
    void successOnTheThirdAttemptIsDegradedAndWaitsAboutOneThenTwoSeconds() {
        source.thenFail(FailureCategory.ERROR_RESPONSE).thenFail(FailureCategory.ERROR_RESPONSE);

        CollectionOutcome outcome = collector(store).collect(source);

        assertThat(outcome.status()).isEqualTo(State.DEGRADED);
        assertThat(outcome.retries()).isEqualTo(2);
        assertThat(source.calls()).isEqualTo(3);
        assertThat(sleeper.waits().get(0)).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
        assertThat(sleeper.waits().get(1)).isBetween(Duration.ofMillis(1600), Duration.ofMillis(2400));
    }

    @Test
    void anInvalidPayloadIsNotRetriedAndFailsAtOnce() {
        source.thenThrow(com.memetrade.marketdata.api.MarketSourceException.invalidPayload("bad"));

        CollectionOutcome outcome = collector(store).collect(source);

        assertThat(outcome.status()).isEqualTo(State.FAILED);
        assertThat(outcome.category()).contains(FailureCategory.INVALID_PAYLOAD);
        assertThat(source.calls()).isEqualTo(1);
    }

    @Test
    void anInterruptedCollectionLeavesNothingHalfWritten() {
        MarketStore failing = failAfterTokens(store);

        assertThatThrownBy(() -> collector(failing).collect(source)).isInstanceOf(IllegalStateException.class);

        assertThat(count("select count(*) from market_observation where source_id = :s"))
                .isZero();
        assertThat(count("select count(*) from observation_sample where source_id = :s"))
                .isZero();
        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    void twoCollectionsInTheSameFiveMinutesKeepOneSampleAndTheLatestCurrentValue() {
        collector(store).collect(source);
        clock.advance(Duration.ofSeconds(30));
        collector(store).collect(source);
        clock.advance(Duration.ofMinutes(5));
        collector(store).collect(source);

        assertThat(count("select count(*) from observation_sample where source_id = :s and field = 'PRICE'"))
                .isEqualTo(4 * 2);
        assertThat(jdbc.sql("select max(observed_at) = :now from market_observation where source_id = :s")
                        .param("s", sourceId)
                        .param("now", java.sql.Timestamp.from(clock.instant()))
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void theGlobalMarkGrowsOnlyWhenAStateChanges() {
        long start = store.statusMark();
        collector(store).collect(source);
        clock.advance(Duration.ofSeconds(30));
        collector(store).collect(source);
        long afterOk = store.statusMark();
        source.thenThrow(com.memetrade.marketdata.api.MarketSourceException.errorResponse(404));
        collector(store).collect(source);

        assertThat(afterOk - start).isEqualTo(1);
        assertThat(store.statusMark() - start).isEqualTo(2);
    }

    @Test
    void aRestartRegistrationNeverRewritesTheStoredState() {
        source.thenThrow(com.memetrade.marketdata.api.MarketSourceException.errorResponse(400));
        collector(store).collect(source);

        new MarketSourceRegistry(List.of(source), store, properties).register(source);
        new MarketSourceRegistry(List.of(source), store, properties).ensureRegistered(source);

        assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.sql("select enabled from data_source where source_id = :s")
                        .param("s", sourceId)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void anOverlappingCollectionOfTheSameSourceIsSkippedByTheLock() {
        var held = lockProvider
                .lock(new LockConfiguration(
                        clock.instant(), "market-collect-sim-b", Duration.ofMinutes(1), Duration.ZERO))
                .orElseThrow();
        try {
            assertThat(job.collectNow("sim-b")).isEmpty();
        } finally {
            held.unlock();
        }
        assertThat(job.collectNow("sim-b")).isPresent();
    }

    @Test
    void theCommonFixtureTokensOfTwoSourcesMeetOnTheSameChainAndMint() {
        ScriptedMarketSource other =
                new ScriptedMarketSource(FixtureMarketSource.load("sim-b").as(sourceId + "b"));
        store.registerSource(other.source(), Duration.ofSeconds(30));
        collector(store).collect(source);
        collector(store).collect(other);

        assertThat(jdbc.sql("select count(distinct source_id) from market_observation where mint = :m")
                        .param("m", FixtureMarketSource.COMMON_A)
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("select count(*) from token where symbol = 'PEPE' and mint in (:a, :b)")
                        .param("a", FixtureMarketSource.ONLY_A)
                        .param("b", FixtureMarketSource.ONLY_B)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void aDiscardedTokenIsCountedAndTheOthersAreStored() {
        source.thenAnswer(result -> {
            List<TokenInfo> tokens = new ArrayList<>(result.tokens());
            tokens.add(new TokenInfo(new TokenId("solana", "short"), "BAD", null, 6));
            return new FetchResult(tokens, result.observations());
        });

        CollectionOutcome outcome = collector(store).collect(source);

        assertThat(outcome.status()).isEqualTo(State.DEGRADED);
        assertThat(outcome.discarded()).containsValue(1);
        assertThat(count("select count(distinct mint) from market_observation where source_id = :s"))
                .isEqualTo(4);
    }

    private MarketCollector collector(MarketStore target) {
        return collector(target, Duration.ofSeconds(5));
    }

    private MarketCollector collector(MarketStore target, Duration timeout) {
        SourceCaller caller =
                new SourceCaller(Executors.newCachedThreadPool(), sleeper, new Random(42), timeout, (s, n) -> {});
        return new MarketCollector(target, caller, new TransactionUnitOfWork(transactions), clock);
    }

    /** A store that fails right after writing the tokens, in the middle of the collection transaction. */
    private MarketStore failAfterTokens(MarketStore real) {
        JdbcMarketStore halfway = new JdbcMarketStore(JdbcClient.create(named.getJdbcTemplate()), named) {
            @Override
            public void writeCollection(CollectionWrite write) {
                write.tokens().forEach(token -> upsertToken(token, write.seenAt()));
                write.values()
                        .forEach((token, fields) -> fields.forEach((field, value) ->
                                upsertObservation(token, write.sourceId(), field, value.value(), value.observedAt())));
                throw new IllegalStateException("injected failure in the middle of the collection");
            }
        };
        return (MarketStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {MarketStore.class}, (proxy, method, args) -> {
                    try {
                        return method.invoke(method.getName().equals("writeCollection") ? halfway : real, args);
                    } catch (java.lang.reflect.InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
    }

    private String status() {
        return jdbc.sql("select status from data_source where source_id = :s")
                .param("s", sourceId)
                .query(String.class)
                .single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).param("s", sourceId).query(Integer.class).single();
    }
}
