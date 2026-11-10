package com.memetrade.privacy.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.Principal;
import com.memetrade.privacy.domain.anonymization.AnonymizationEvents;
import com.memetrade.privacy.domain.anonymization.AnonymizationResult;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * J6 of u5-anonymization (NFR8.2; BR5.2): ACCOUNT_ANONYMIZED through the trail, in the transaction of the anonymization
 * (the outbox row commits or rolls back with it). Actor the administrator, tenant of the person, target the internal
 * identifier of the account ({@code user:<id>}), details ONLY {@code botsStopped}, {@code sessionsRevoked} and
 * {@code feedbackDeleted}; never the wallet, a name or a text.
 */
@Component
public class AuditAnonymizationEvents implements AnonymizationEvents {

    private final AuditEventPublisher trail;
    private final Clock clock;

    public AuditAnonymizationEvents(AuditEventPublisher trail, Clock clock) {
        this.trail = trail;
        this.clock = clock;
    }

    @Override
    public void accountAnonymized(DataSubjectRequest request, AnonymizationResult result, Principal admin) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("botsStopped", Integer.toString(result.botsStopped()));
        details.put("sessionsRevoked", Integer.toString(result.sessionsRevoked()));
        details.put("feedbackDeleted", Integer.toString(result.feedbackDeleted()));
        trail.publish(new AuditEventCommand(
                UUID.randomUUID(),
                request.tenantId(),
                admin.userId().toString(),
                ActorType.ADMIN,
                AuditActions.ACCOUNT_ANONYMIZED,
                "user:" + result.userId(),
                CorrelationId.current(),
                clock.instant(),
                details));
    }
}
