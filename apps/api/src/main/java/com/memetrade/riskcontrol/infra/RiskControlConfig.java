package com.memetrade.riskcontrol.infra;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.domain.EvaluationErrors;
import com.memetrade.riskcontrol.domain.EvaluationRepository;
import com.memetrade.riskcontrol.domain.LimitChecks;
import com.memetrade.riskcontrol.domain.OrderGateService;
import com.memetrade.riskcontrol.domain.RejectionEvents;
import com.memetrade.riskcontrol.domain.RejectionQueries;
import com.memetrade.riskcontrol.domain.RejectionRepository;
import com.memetrade.riskcontrol.domain.SlippageEstimate;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the pure domain of RiskControl: the checks with the copy of policy v1 and the fixed freshness limit, and
 * the ONLY {@link OrderGate} bean, the pure {@link OrderGateService} inside {@link TransactionalOrderGate} (the service
 * itself is never a bean, so nothing can call it outside the cycle transaction).
 */
@Configuration(proxyBeanMethods = false)
public class RiskControlConfig {

    @Bean
    public LimitChecks limitChecks(RiskControlProperties properties) {
        return new LimitChecks(SlippageEstimate.V1, properties.freshnessMaxAge());
    }

    /** F3: the reading of C21 by the person of the token. */
    @Bean
    public RejectionQueries rejectionQueries(EvaluationRepository evaluations, RejectionRepository rejections) {
        return new RejectionQueries(evaluations, rejections);
    }

    @Bean
    public OrderGate orderGate(
            MarketDataQuery market,
            PortfolioRiskView portfolio,
            EvaluationRepository evaluations,
            RejectionRepository rejections,
            RejectionEvents events,
            AuthContext auth,
            Clock clock,
            LimitChecks checks,
            EvaluationErrors errors) {
        return new TransactionalOrderGate(
                new OrderGateService(market, portfolio, evaluations, rejections, events, auth, clock, checks, errors));
    }
}
