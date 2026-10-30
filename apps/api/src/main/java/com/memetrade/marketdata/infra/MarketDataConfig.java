package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** U1 wires only the simulated source; real adapters arrive in U4 behind the same port. */
@Configuration
public class MarketDataConfig {

    @Bean
    public MarketSourceAdapter marketSourceAdapter(MarketDataProperties properties, Clock clock) {
        return new SimulatedMarketSource(
                properties.simulated().seed(), properties.simulated().tokenCount(), clock);
    }

    @Bean
    public MarketCollector marketCollector(MarketSourceAdapter adapter, MarketStore store, Clock clock) {
        return new MarketCollector(adapter, store, clock);
    }
}
