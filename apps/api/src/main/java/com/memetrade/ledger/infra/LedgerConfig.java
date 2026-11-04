package com.memetrade.ledger.infra;

import com.memetrade.ledger.domain.FillCalculator;
import com.memetrade.ledger.domain.SimulationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the pure domain of the ledger: the calculator of the policy in force, v1 (BR1.1). */
@Configuration(proxyBeanMethods = false)
public class LedgerConfig {

    @Bean
    public FillCalculator fillCalculator() {
        return new FillCalculator(SimulationPolicy.V1);
    }
}
