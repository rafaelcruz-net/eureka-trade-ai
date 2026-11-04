package com.memetrade.scanner.infra;

import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.scanner.domain.ScannerService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScannerConfig {

    @Bean
    public ScannerService scannerService(MarketListingQuery market, RiskAssessmentQuery risk, Clock clock) {
        return new ScannerService(market, risk, clock);
    }

    /** Per person and route limit of the list and the detail (NFR6.3). */
    @Bean
    public com.memetrade.shared.web.PersonRouteRateLimiter scannerRateLimiter(
            ScannerProperties properties, Clock clock) {
        return new com.memetrade.shared.web.PersonRouteRateLimiter(properties.rateLimitPerMinute(), clock);
    }
}
