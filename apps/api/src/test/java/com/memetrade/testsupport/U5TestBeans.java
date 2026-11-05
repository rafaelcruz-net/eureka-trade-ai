package com.memetrade.testsupport;

import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import com.memetrade.marketdata.infra.JdbcMarketListingQuery;
import com.memetrade.riskassessment.infra.RiskAssessmentService;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The doubles of C11, C12 and the scanner shared by the two U5 test contexts (api and worker). U6 and U7 stay REAL in
 * every integration test of U5 (unit-test-instructions.md); only the market data and the assessment are scripted.
 */
@TestConfiguration(proxyBeanMethods = false)
public class U5TestBeans {

    @Bean
    @Primary
    public U5MarketData u5MarketData(JdbcMarketDataQuery real, Clock clock) {
        return new U5MarketData(real, clock);
    }

    @Bean
    @Primary
    public U5Assessments u5Assessments(RiskAssessmentService real) {
        return new U5Assessments(real);
    }

    @Bean
    @Primary
    public U5Listing u5Listing(JdbcMarketListingQuery real) {
        return new U5Listing(real);
    }
}
