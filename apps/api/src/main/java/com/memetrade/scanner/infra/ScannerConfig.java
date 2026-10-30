package com.memetrade.scanner.infra;

import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.scanner.domain.ScannerService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScannerConfig {

    @Bean
    public ScannerService scannerService(MarketListingQuery market, Clock clock) {
        return new ScannerService(market, clock);
    }
}
