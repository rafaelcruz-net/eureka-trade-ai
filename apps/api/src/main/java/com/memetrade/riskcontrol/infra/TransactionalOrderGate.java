package com.memetrade.riskcontrol.infra;

import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.domain.OrderGateService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link OrderGate} bean of C14 (created by {@link RiskControlConfig}): the pure {@link OrderGateService} inside
 * the cycle transaction opened by U5. {@code MANDATORY}: without a transaction the call fails before reading or writing
 * anything (BR1.1); a technical failure marks the cycle transaction rollback-only, so nothing of the evaluation
 * survives (NFR4.6, NFR10.2).
 */
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalOrderGate implements OrderGate {

    private final OrderGateService service;

    public TransactionalOrderGate(OrderGateService service) {
        this.service = service;
    }

    @Override
    public GateResult evaluate(CandidateOrder order, BotLimitsVersionRef limits) {
        return service.evaluate(order, limits);
    }
}
