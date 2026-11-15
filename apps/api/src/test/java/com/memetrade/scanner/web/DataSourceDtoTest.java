package com.memetrade.scanner.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.marketdata.api.Attribution;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.SourceKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Step 11 (BR1.5, BR1.8, NFR7.1): the {@code dataSource} of the scanner page and of the assessment detail. */
class DataSourceDtoTest {

    private final ObjectMapper json = new ObjectMapper();

    private static MarketDataQuery market(SourceKind kind, Optional<Attribution> attribution) {
        MarketDataQuery market = Mockito.mock(MarketDataQuery.class);
        Mockito.when(market.activeSourceKind()).thenReturn(kind);
        Mockito.when(market.attribution()).thenReturn(attribution);
        return market;
    }

    @Test
    void aRealSourceCarriesTheAttributionOfItsProvider() throws Exception {
        ScannerDtos.DataSourceDto dto = ScannerDtos.DataSourceDto.of(market(
                SourceKind.REAL, Optional.of(new Attribution("Dados: CoinGecko", "https://www.coingecko.com/"))));

        assertThat(json.writeValueAsString(dto))
                .isEqualTo("{\"kind\":\"REAL\",\"attribution\":{\"text\":\"Dados: CoinGecko\","
                        + "\"url\":\"https://www.coingecko.com/\"}}");
    }

    @Test
    void aSimulatedSourceHasNoAttributionAndTheFieldIsLeftOut() throws Exception {
        ScannerDtos.DataSourceDto dto = ScannerDtos.DataSourceDto.of(market(SourceKind.SIMULATED, Optional.empty()));

        assertThat(json.writeValueAsString(dto)).isEqualTo("{\"kind\":\"SIMULATED\"}");
    }

    @Test
    void theScannerPageAlwaysCarriesTheDataSource() throws Exception {
        ScannerDtos.ScannerPageDto page = ScannerDtos.ScannerPageDto.of(
                new com.memetrade.scanner.domain.ScannerPage(
                        java.util.List.of(), Optional.empty(), 0, java.util.List.of(), 0, 0),
                ScannerDtos.DataSourceDto.of(market(SourceKind.SIMULATED, Optional.empty())));

        assertThat(json.readTree(json.writeValueAsString(page))
                        .get("dataSource")
                        .get("kind")
                        .asText())
                .isEqualTo("SIMULATED");
    }
}
