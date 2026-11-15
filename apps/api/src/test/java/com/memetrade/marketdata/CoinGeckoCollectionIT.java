package com.memetrade.marketdata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.domain.SourceFetcher;
import com.memetrade.marketdata.infra.TransactionUnitOfWork;
import com.memetrade.marketdata.infra.coingecko.CallBudget;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoAdapter;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoFixtures;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoHttp;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoMetrics;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoProperties;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoSource;
import com.memetrade.marketdata.infra.coingecko.JdbcUsageStore;
import com.memetrade.testsupport.AbstractPostgresIT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Steps 6 and 7 end to end on PostgreSQL (BR1.2, BR1.9, US6.3): the CoinGecko adapter (WireMock, synthetic answers)
 * through the production {@link SourceFetcher} (25 s, one attempt) and a {@link MarketCollector} into the MarketData
 * tables, read back through M4. The provider going down gives DEGRADED with the old values and their time, then FAILED
 * with {@code Unknown(SOURCE_UNAVAILABLE)}; never zero, never a switch to a simulated source.
 */
class CoinGeckoCollectionIT extends AbstractPostgresIT {

    @RegisterExtension
    static WireMockExtension wire = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final List<String> MINTS =
            List.of(CoinGeckoFixtures.mint(501), CoinGeckoFixtures.mint(502), CoinGeckoFixtures.mint(503));

    @Autowired
    private MarketStore store;

    @Autowired
    private SourceFetcher fetcher;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MarketDataQuery market;

    private MarketCollector collector;
    private CoinGeckoAdapter adapter;

    @BeforeEach
    void setUp() {
        store.registerSource(CoinGeckoSource.REF, Duration.ofSeconds(30));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CoinGeckoHttp http = new CoinGeckoHttp(
                wire.baseUrl() + "/api/v3",
                "x-cg-pro-api-key",
                CoinGeckoFixtures.TEST_KEY,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
        CallBudget budget = new CallBudget(
                "coingecko", 250, 2, 450_000, new JdbcUsageStore(jdbc, transactionManager), clock, meters);
        adapter = new CoinGeckoAdapter(
                CoinGeckoProperties.defaults().withBaseUrl(wire.baseUrl() + "/api/v3"),
                http,
                budget,
                clock,
                new CoinGeckoMetrics(meters, "coingecko"));
        collector = new MarketCollector(store, fetcher, new TransactionUnitOfWork(transactions), clock);
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/new_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(MINTS))));
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/trending_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(List.of()))));
        wire.stubFor(get(urlPathMatching(".*/tokens/.*/info"))
                .willReturn(okJson(CoinGeckoFixtures.info(MINTS.get(0), "0"))));
        wire.stubFor(get(urlPathMatching(".*/tokens/.*/pools"))
                .willReturn(okJson(CoinGeckoFixtures.tokenPools("2026-10-27T08:00:00Z"))));
        pricesUp();
    }

    /** Like the other integration tests with a source of their own: the shared database keeps sim-a and sim-b only. */
    @org.junit.jupiter.api.AfterEach
    void disableTheSource() {
        jdbc.sql("update data_source set enabled = false where source_id = 'coingecko'")
                .update();
    }

    private void pricesUp() {
        wire.stubFor(get(urlPathMatching(".*/token_price/.*")).willReturn(okJson(CoinGeckoFixtures.prices(MINTS))));
    }

    private State status() {
        return store.sourceState("coingecko").orElseThrow().status();
    }

    @Test
    void theProviderGoingDownGivesDegradedWithTheOldValueThenFailedAndUnknownThenOkAgain() {
        TokenId token = new TokenId("solana", MINTS.get(0));
        Instant first = clock.instant();

        assertThat(collector.collect(adapter).status()).isEqualTo(State.OK);
        MarketValue price = market.referencePrice(token);
        assertThat(price).isInstanceOf(MarketValue.Known.class);
        assertThat(((MarketValue.Known) price).value()).isEqualByComparingTo("1.5");
        assertThat(((MarketValue.Known) price).source().simulated()).isFalse();
        assertThat(((MarketValue.Known) price).observedAt()).isEqualTo(first);

        wire.stubFor(
                get(urlPathMatching(".*/token_price/.*")).willReturn(aResponse().withStatus(503)));
        clock.advance(Duration.ofSeconds(30));
        assertThat(collector.collect(adapter).status()).isEqualTo(State.DEGRADED);
        MarketValue kept = market.referencePrice(token);
        assertThat(((MarketValue.Known) kept).observedAt())
                .as("the old value keeps its own time")
                .isEqualTo(first);

        clock.advance(Duration.ofSeconds(30));
        assertThat(collector.collect(adapter).status()).isEqualTo(State.FAILED);
        assertThat(market.referencePrice(token))
                .isEqualTo(new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, java.util.Optional.empty()));

        pricesUp();
        clock.advance(Duration.ofSeconds(30));
        assertThat(collector.collect(adapter).status()).isEqualTo(State.OK);
        assertThat(((MarketValue.Known) market.referencePrice(token)).observedAt())
                .isEqualTo(clock.instant());
    }

    @Test
    void anInformedZeroHoldersIsKnownZeroAndTheSourceIsNeverSimulated() {
        collector.collect(adapter);

        assertThat(jdbc.sql("select status, value from market_observation where source_id = 'coingecko'"
                                + " and field = 'HOLDERS' and mint = :mint")
                        .param("mint", MINTS.get(0))
                        .query()
                        .singleRow())
                .containsEntry("status", "KNOWN")
                .satisfies(row ->
                        assertThat((java.math.BigDecimal) row.get("value")).isEqualByComparingTo("0"));
        assertThat(jdbc.sql("select simulated from data_source where source_id = 'coingecko'")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(status()).isIn(State.OK, State.DEGRADED);
    }
}
