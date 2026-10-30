package com.memetrade.identity.infra;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.shared.web.ForbiddenRequestRecorder;
import com.memetrade.shared.web.RequestPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records every 403 as exactly one REQUEST_FORBIDDEN with route, role and reason in its own transaction (BR1.7,
 * BR2.1, BR4.2, ADR-0003).
 *
 * <p>The route is never the raw client URI: it is {@link RequestPaths#auditRoute} (normalised path, no query string,
 * unsafe segments replaced by {@code {redacted}}, at most 200 characters), so a long path or a path carrying a token,
 * a signature or a wallet address can neither leak into the trail nor make the publisher refuse the event, which used
 * to turn the 403 into a 500 without any event (review finding). If the publisher still refuses the event, it is
 * recorded once more with a fixed route, so the refusal always leaves its event and always answers 403.
 */
@Component
public class AuditedForbiddenRequestRecorder implements ForbiddenRequestRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditedForbiddenRequestRecorder.class);

    static final String FALLBACK_ROUTE = "UNRECORDABLE " + RequestPaths.REDACTED;

    private final AuditEventPublisher audit;
    private final AuthContext authContext;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuditedForbiddenRequestRecorder(
            AuditEventPublisher audit, AuthContext authContext, TransactionTemplate transactions, Clock clock) {
        this.audit = audit;
        this.authContext = authContext;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void record(HttpServletRequest request, ErrorCode code, String reason) {
        Optional<Principal> principal = authContext.optional();
        try {
            publish(principal, RequestPaths.auditRoute(request), code, reason);
        } catch (AuditPublishRejectedException refused) {
            // A defect of the sanitising above, never of the client: log field and reason only, keep the one event.
            log.error(
                    "REQUEST_FORBIDDEN refused by the publisher, recorded with a fixed route: field={}, reason={}",
                    refused.field(),
                    refused.reason());
            publish(principal, FALLBACK_ROUTE, code, reason);
        }
    }

    private void publish(Optional<Principal> principal, String route, ErrorCode code, String reason) {
        Map<String, String> details = Map.of(
                "route",
                route,
                "role",
                principal.map(p -> p.role().name()).orElse("NONE"),
                "code",
                code.name(),
                "reason",
                reason == null ? code.name() : reason);
        AuditEventCommand event = principal
                .map(p -> AuditEventCommand.person(
                        p.tenantId(),
                        p.userId(),
                        AuditActions.REQUEST_FORBIDDEN,
                        route,
                        CorrelationId.current(),
                        clock.instant(),
                        details))
                .orElseGet(() -> AuditEventCommand.anonymous(
                        AuditActions.REQUEST_FORBIDDEN,
                        "anonymous",
                        route,
                        CorrelationId.current(),
                        clock.instant(),
                        details));
        transactions.executeWithoutResult(status -> audit.publish(event));
    }
}
