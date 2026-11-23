package com.memetrade.pnl.infra;

import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.pnl.real.RealCostBasis;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 261121-paper-real-unico U2 (C11: "o bean existe em todo processo que usa o ledger REAL"): the one REAL average cost
 * method, {@link RealCostBasis}, in the api and in the worker. The ledger declares the port and never depends on the
 * P&L; the P&L provides it here.
 */
@Configuration(proxyBeanMethods = false)
public class RealCostConfig {

    @Bean
    public RealCostMethod realCostMethod() {
        return new RealCostBasis();
    }
}
