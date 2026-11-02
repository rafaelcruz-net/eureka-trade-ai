package com.memetrade.riskassessment.infra;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.Principal;
import com.memetrade.riskassessment.api.RiskWeightAdministration;
import com.memetrade.riskassessment.api.WeightSetDraft;
import com.memetrade.riskassessment.api.WeightSetStatus;
import com.memetrade.riskassessment.api.WeightSetView;
import com.memetrade.riskassessment.domain.WeightSetValidator;
import com.memetrade.riskassessment.domain.WeightSetValidator.Violation;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import com.memetrade.shared.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F7 (BR4.1, BR4.2, NFR6.4): every operation first requires an administrator through {@link AdminAccess} (the single
 * component that decides on the ADMIN role); a refusal records one {@code REQUEST_FORBIDDEN} and changes nothing. An
 * activation takes the activation lock, retires the ACTIVE version, activates the DRAFT and publishes one
 * {@code RISK_WEIGHTS_ACTIVATED} in the same transaction; after the commit every assessment is recalculated.
 */
public class RiskWeightAdministrationService implements RiskWeightAdministration {

    private static final Logger log = LoggerFactory.getLogger(RiskWeightAdministrationService.class);

    private final AdminAccess adminAccess;
    private final JdbcRiskWeightStore store;
    private final AuditEventPublisher audit;
    private final TransactionTemplate transactions;
    private final RiskRecalculator recalculator;
    private final Clock clock;

    public RiskWeightAdministrationService(
            AdminAccess adminAccess,
            JdbcRiskWeightStore store,
            AuditEventPublisher audit,
            TransactionTemplate transactions,
            RiskRecalculator recalculator,
            Clock clock) {
        this.adminAccess = adminAccess;
        this.store = store;
        this.audit = audit;
        this.transactions = transactions;
        this.recalculator = recalculator;
        this.clock = clock;
    }

    @Override
    public WeightSetView create(WeightSetDraft draft, HttpServletRequest request) {
        Principal admin = adminAccess.requireAdministrator(request);
        requireValid(draft);
        Integer version = transactions.execute(
                status -> store.insertDraft(draft, admin.userId().toString(), clock.instant()));
        return store.find(version).orElseThrow();
    }

    @Override
    public WeightSetView update(int version, WeightSetDraft draft, HttpServletRequest request) {
        adminAccess.requireAdministrator(request);
        WeightSetView current = existing(version);
        if (current.status() != WeightSetStatus.DRAFT) {
            throw notEditable(version);
        }
        requireValid(draft);
        transactions.executeWithoutResult(status -> store.updateDraft(version, draft));
        return store.find(version).orElseThrow();
    }

    @Override
    public ActivationResult activate(int version, HttpServletRequest request) {
        Principal admin = adminAccess.requireAdministrator(request);
        ActivationResult result = transactions.execute(status -> activateLocked(version, admin));
        if (result.changed()) {
            log.info(
                    "Risk weights activated: fromVersion={}, toVersion={}, actor={}",
                    result.retiredVersion().map(String::valueOf).orElse("NONE"),
                    result.activeVersion(),
                    admin.userId());
            recalculator.onWeightsActivated();
        }
        return result;
    }

    @Override
    public List<WeightSetView> list(HttpServletRequest request) {
        adminAccess.requireAdministrator(request);
        return store.list();
    }

    private ActivationResult activateLocked(int version, Principal admin) {
        store.lockActivations();
        WeightSetView target = existing(version);
        if (target.status() == WeightSetStatus.ACTIVE) {
            return new ActivationResult(version, Optional.empty(), false);
        }
        if (target.status() == WeightSetStatus.RETIRED) {
            throw notEditable(version);
        }
        int previous = store.active().version();
        Instant now = clock.instant();
        store.retire(previous, now);
        store.activate(version, now);
        audit.publish(AuditEventCommand.admin(
                admin.userId(),
                AuditActions.RISK_WEIGHTS_ACTIVATED,
                "risk-weight-set:" + version,
                CorrelationId.current(),
                now,
                Map.of("fromVersion", Integer.toString(previous), "toVersion", Integer.toString(version))));
        return new ActivationResult(version, Optional.of(previous), true);
    }

    private WeightSetView existing(int version) {
        return store.find(version)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Weight version not found."));
    }

    private static void requireValid(WeightSetDraft draft) {
        List<Violation> violations = WeightSetValidator.validate(draft);
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "One or more fields are invalid.",
                    violations.stream()
                            .map(v -> new FieldViolation(v.field(), v.message()))
                            .toList(),
                    null,
                    Map.of());
        }
    }

    private static ApiException notEditable(int version) {
        return new ApiException(
                ErrorCode.WEIGHT_SET_NOT_EDITABLE,
                "Weight version " + version + " is ACTIVE or RETIRED and cannot change.");
    }
}
