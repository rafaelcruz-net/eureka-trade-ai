package com.memetrade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.botwallet.AbstractRealIT;
import com.memetrade.marketdata.api.Attribution;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoAdapter;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoCollectorJob;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoHttp;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoUsageJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * Steps 10 and 11 (BR1.5, BR1.6, BR1.8, NFR1.5, NFR1.6, cicd-pipeline.md): the API starts with REAL on,
 * {@code MARKET_SOURCES=coingecko}, no {@code test}/{@code local} profile and WITHOUT {@code COINGECKO_API_KEY}; it has
 * no CoinGecko client, adapter or job (only the worker calls the provider), answers REAL with the attribution, and the
 * scanner page carries it.
 */
@TestPropertySource(properties = {"memetrade.real.enabled=true", "memetrade.market.sources=coingecko"})
class CoinGeckoApiStartupIT extends AbstractRealIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private MarketDataQuery market;

    @BeforeEach
    void onlyTheRealSourceIsEnabledAndWaitsForItsFirstCollection() {
        jdbc.sql("update data_source set enabled = false where source_id in ('sim-a', 'sim-b')")
                .update();
        jdbc.sql("""
                        update data_source set enabled = true, status = 'PENDING', failure_category = null,
                               last_updated_at = null
                        where source_id = 'coingecko'""").update();
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
    void theApiStartsWithTheRealSourceWithoutTheKeyAndNeverBuildsTheClient() {
        assertThat(environment.getProperty("COINGECKO_API_KEY")).isNull();
        assertThat(environment.getActiveProfiles()).doesNotContain("test", "local");
        assertThat(context.getBeanNamesForType(CoinGeckoHttp.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CoinGeckoAdapter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CoinGeckoCollectorJob.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CoinGeckoUsageJob.class)).isEmpty();
        assertThat(market.activeSourceKind()).isEqualTo(SourceKind.REAL);
        assertThat(market.attribution()).contains(new Attribution("Dados: CoinGecko", "https://www.coingecko.com/"));
        assertThat(jdbc.sql("select simulated from data_source where source_id = 'coingecko'")
                        .query(Boolean.class)
                        .single())
                .as("registered by the api too, never simulated")
                .isFalse();
    }

    @Test
    void theScannerPageCarriesTheKindOfTheSourceAndTheAttribution() throws Exception {
        Person person = signIn(true, false);

        MockHttpServletResponse response = call(person, get("/api/v1/scanner/tokens"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode page = body(response);
        assertThat(page.get("dataSource").get("kind").asText()).isEqualTo("REAL");
        assertThat(page.get("dataSource").get("attribution").get("text").asText())
                .isEqualTo("Dados: CoinGecko");
        assertThat(page.get("dataSource").get("attribution").get("url").asText())
                .isEqualTo("https://www.coingecko.com/");
        assertThat(page.get("sources").toString()).contains("coingecko").doesNotContain("sim-a");
    }
}
