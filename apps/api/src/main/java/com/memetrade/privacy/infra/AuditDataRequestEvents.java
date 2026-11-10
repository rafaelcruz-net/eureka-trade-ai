package com.memetrade.privacy.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.privacy.domain.request.Actor;
import com.memetrade.privacy.domain.request.DataRequestEvents;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link DataRequestEvents} through the audit trail (J6) in the transaction of the change (BR4.6; NFR1.1, NFR8.4). Each
 * event carries the tenant of the request when it has one, targets the linked account ({@code user:<id>}) or the
 * request ({@code data-request:<id>}), and has only {@code requestId}, {@code type}, {@code origin}, {@code from} and
 * {@code to} as details: never the text, the contact, the response, the reason, the message or the signature (BR4.3).
 * The logs carry the same identifiers and states only.
 */
@Component
public class AuditDataRequestEvents implements DataRequestEvents {

    private static final Logger log = LoggerFactory.getLogger(AuditDataRequestEvents.class);

    private final AuditEventPublisher audit;
    private final DataRequestMetrics metrics;
    private final Clock clock;

    public AuditDataRequestEvents(AuditEventPublisher audit, DataRequestMetrics metrics, Clock clock) {
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void created(DataSubjectRequest request, Actor actor) {
        publish(AuditActions.DATA_REQUEST_CREATED, request, Optional.empty(), actor);
        metrics.requestCreated(request.type(), request.origin());
    }

    @Override
    public void transitioned(DataSubjectRequest request, DataRequestStatus from, Actor actor) {
        publish(AuditActions.DATA_REQUEST_TRANSITIONED, request, Optional.of(from), actor);
    }

    @Override
    public void verificationStarted(DataSubjectRequest request, Actor actor) {
        publish(
                AuditActions.DATA_REQUEST_VERIFICATION_STARTED,
                request,
                Optional.of(DataRequestStatus.RECEIVED),
                actor);
    }

    @Override
    public void verified(DataSubjectRequest request, Actor actor) {
        publish(
                AuditActions.DATA_REQUEST_VERIFIED,
                request,
                Optional.of(DataRequestStatus.AWAITING_VERIFICATION),
                actor);
    }

    @Override
    public void overdue(DataSubjectRequest request) {
        publish(AuditActions.DATA_REQUEST_OVERDUE, request, Optional.empty(), Actor.system());
    }

    private void publish(String action, DataSubjectRequest request, Optional<DataRequestStatus> from, Actor actor) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("requestId", request.requestId().toString());
        details.put("type", request.type().name());
        details.put("origin", request.origin().name());
        from.ifPresent(state -> details.put("from", state.name()));
        details.put("to", request.status().name());
        audit.publish(new AuditEventCommand(
                UUID.randomUUID(),
                request.tenantId(),
                actorId(actor),
                actorType(actor),
                action,
                targetRef(request),
                CorrelationId.current(),
                clock.instant(),
                details));
        log.info(
                "Data request event: action={}, requestId={}, type={}, origin={}, from={}, to={}",
                action,
                request.requestId(),
                request.type(),
                request.origin(),
                from.map(Enum::name).orElse("-"),
                request.status());
    }

    /** The linked account by its identifier, or the request itself. */
    static String targetRef(DataSubjectRequest request) {
        return request.userId().map(user -> "user:" + user).orElse("data-request:" + request.requestId());
    }

    private static String actorId(Actor actor) {
        return switch (actor) {
            case Actor.Person person -> person.principal().userId().toString();
            case Actor.Admin admin -> admin.principal().userId().toString();
            case Actor.SystemActor system -> AuditEventCommand.SYSTEM_ACTOR;
        };
    }

    private static ActorType actorType(Actor actor) {
        return switch (actor) {
            case Actor.Person person -> ActorType.PERSON;
            case Actor.Admin admin -> ActorType.ADMIN;
            case Actor.SystemActor system -> ActorType.SYSTEM;
        };
    }
}
