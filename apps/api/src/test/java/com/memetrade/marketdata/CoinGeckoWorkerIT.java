package com.memetrade.marketdata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoAdapter;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoCollectorJob;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoFixtures;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoHttp;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoUsageJob;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Review fix (Major, NFR1.6, BR1.1, BR1.9, NFR3.7): the WORKER Spring context with {@code MARKET_SOURCES=coingecko},
 * the test profile (WireMock instead of a CoinGecko host), a test key and the PostgreSQL of Testcontainers. The
 * CoinGecko beans exist only here, and {@code collectNow()} goes through the real wiring (SourceCaller with 25 s and
 * one attempt, the collector, the ShedLock lock, the call counter in {@code marketdata_provider_usage}) and writes the
 * observations to PostgreSQL. No real provider is ever called.
 */
@ActiveProfiles({"test", "worker"})
@TestPropertySource(
        properties = {"memetrade.market.sources=coingecko", "COINGECKO_API_KEY=" + CoinGeckoFixtures.TEST_KEY})
class CoinGeckoWorkerIT extends AbstractU5WorkerIT {

    /** Started before the context, so its port can be the base URL of the source. */
    static final WireMockServer WIRE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE.start();
    }

    private static final List<String> MINTS =
            List.of(CoinGeckoFixtures.mint(701), CoinGeckoFixtures.mint(702), CoinGeckoFixtures.mint(703));

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("memetrade.market.coingecko.base-url", () -> WIRE.baseUrl() + "/api/v3");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CoinGeckoCollectorJob job;

    @BeforeEach
    void stubTheProvider() {
        WIRE.resetAll();
        WIRE.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/new_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(MINTS))));
        WIRE.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/trending_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(List.of()))));
        WIRE.stubFor(get(urlPathMatching(".*/token_price/.*")).willReturn(okJson(CoinGeckoFixtures.prices(MINTS))));
        WIRE.stubFor(get(urlPathMatching(".*/tokens/.*/info"))
                .willReturn(okJson(CoinGeckoFixtures.info(MINTS.get(0), "1500"))));
        WIRE.stubFor(get(urlPathMatching(".*/tokens/.*/pools"))
                .willReturn(okJson(CoinGeckoFixtures.tokenPools("2026-10-27T08:00:00Z"))));
        WIRE.stubFor(get(urlPathEqualTo("/api/v3/key")).willReturn(aResponse().withStatus(404)));
        jdbc.sql("update data_source set enabled = true where source_id = 'coingecko'")
                .update();
    }

    /** The shared database goes back to the simulated sources of the other integration tests. */
    @AfterEach
    void backToTheSimulatedSources() {
        jdbc.sql("update data_source set enabled = true where source_id in ('sim-a', 'sim-b')")
                .update();
        jdbc.sql("update data_source set enabled = false where source_id = 'coingecko'")
                .update();
    }

    @Test
    void theWorkerBuildsTheCoinGeckoBeansWithTheKeyFromItsEnvironment() {
        assertThat(context.getBeanNamesForType(CoinGeckoCollectorJob.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(CoinGeckoUsageJob.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(CoinGeckoAdapter.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(CoinGeckoHttp.class)).hasSize(1);
        assertThat(context.getBean(CoinGeckoHttp.class).toString()).doesNotContain(CoinGeckoFixtures.TEST_KEY);
    }

    @Test
    void aCollectionThroughTheWorkerWritesTheObservationsToPostgresql() {
        assertThat(job.collectNow())
                .hasValueSatisfying(outcome -> assertThat(outcome.status()).isEqualTo(State.OK));

        assertThat(jdbc.sql("""
                        select count(*) from market_observation
                        where source_id = 'coingecko' and field = 'PRICE' and status = 'KNOWN' and mint in (:mints)""").param("mints", MINTS).query(Long.class).single())
                .isEqualTo(3);
        assertThat(jdbc.sql("select simulated from data_source where source_id = 'coingecko'")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("select calls from marketdata_provider_usage where source = 'coingecko' and month = :m")
                        .param("m", clock.instant().toString().substring(0, 7))
                        .query(Long.class)
                        .single())
                .as("every call counted before it was made")
                .isPositive();
        WIRE.verify(getRequestedFor(urlPathMatching(".*/token_price/.*"))
                .withHeader("x-cg-pro-api-key", WireMock.equalTo(CoinGeckoFixtures.TEST_KEY)));
    }
}
