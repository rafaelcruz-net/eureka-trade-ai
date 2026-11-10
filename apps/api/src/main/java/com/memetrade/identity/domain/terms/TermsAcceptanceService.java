package com.memetrade.identity.domain.terms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * u3-reacceptance (F2; BR3.4 to BR3.6; NFR2.1, NFR3.1, NFR3.5, NFR8.2, NFR8.3): acceptance of the effective current
 * version of the terms, in ONE transaction:
 *
 * <ol>
 *   <li>the common idempotency mechanism claims the key (the same key and version replay the stored answer; another
 *       version with the same key is {@code 409 IDEMPOTENCY_CONFLICT});
 *   <li>a version that is not the effective current one is {@code 409 TERMS_VERSION_OUTDATED} with
 *       {@code currentVersion}; nothing is recorded and the key is released;
 *   <li>{@code INSERT ... ON CONFLICT DO NOTHING} of a {@code REACCEPTANCE} with the tenant and the person of the
 *       principal and the instant of the clock; the stored row is read back, so a version already accepted (or a
 *       concurrent acceptance that won the race) answers its existing status;
 *   <li>only an effective insertion publishes {@code TERMS_ACCEPTED} (version and source, never the wallet), counts the
 *       acceptance and writes one INFO line with the version and the source only.
 * </ol>
 */
public class TermsAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(TermsAcceptanceService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TermsAcceptanceStore store;
    private final CurrentTerms terms;
    private final IdempotencyGuard idempotency;
    private final AuditEventPublisher audit;
    private final TermsEvents events;
    private final Clock clock;

    public TermsAcceptanceService(
            TermsAcceptanceStore store,
            CurrentTerms terms,
            IdempotencyGuard idempotency,
            AuditEventPublisher audit,
            TermsEvents events,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public Result accept(Acceptance acceptance) {
        IdempotentResponse response = idempotency.execute(
                request(acceptance),
                () -> IdempotentOutcome.of(IdempotentResponse.fresh(200, json(record(acceptance)))));
        return new Result(read(response.body()), response.replayed());
    }

    private TermsStatus record(Acceptance acceptance) {
        String current = terms.current().version();
        if (!current.equals(acceptance.version())) {
            throw ApiException.withCurrentVersion(
                    ErrorCode.TERMS_VERSION_OUTDATED,
                    "The accepted version is not the current version of the terms.",
                    current);
        }
        Principal person = acceptance.person();
        Instant now = clock.instant();
        boolean inserted = store.insertIfAbsent(new TermsAcceptanceStore.NewAcceptance(
                UUID.randomUUID(),
                person.userId(),
                person.tenantId(),
                current,
                now,
                TermsAcceptanceSource.REACCEPTANCE));
        Instant acceptedAt = store.acceptedAt(person.userId(), person.tenantId(), current)
                .orElseThrow(() -> new IllegalStateException("An acceptance of the current terms could not be read"));
        if (inserted) {
            published(acceptance, current, now);
        }
        return new TermsStatus(current, current, acceptedAt, true);
    }

    private void published(Acceptance acceptance, String version, Instant now) {
        Principal person = acceptance.person();
        audit.publish(AuditEventCommand.person(
                person.tenantId(),
                person.userId(),
                AuditActions.TERMS_ACCEPTED,
                "terms:" + version,
                acceptance.correlationId(),
                now,
                Map.of("version", version, "source", TermsAcceptanceSource.REACCEPTANCE.name())));
        events.accepted(TermsAcceptanceSource.REACCEPTANCE);
        log.info("Terms accepted: version={}, source={}", version, TermsAcceptanceSource.REACCEPTANCE);
    }

    private static IdempotencyRequest request(Acceptance acceptance) {
        return new IdempotencyRequest(
                IdempotentOperation.TERMS_ACCEPT,
                IdempotencyScope.authenticated(acceptance.person(), IdempotentOperation.TERMS_ACCEPT),
                acceptance.key(),
                digest(acceptance.version()));
    }

    private static String json(TermsStatus status) {
        ObjectNode node = JSON.createObjectNode()
                .put("acceptedVersion", status.acceptedVersion())
                .put("currentVersion", status.currentVersion())
                .put("acceptedAt", status.acceptedAt().toString())
                .put("upToDate", status.upToDate());
        return node.toString();
    }

    /** The stored body may come back normalised by the database (key order, spaces): read it as JSON. */
    private static TermsStatus read(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return new TermsStatus(
                    node.path("acceptedVersion").asText(),
                    node.path("currentVersion").asText(),
                    Instant.parse(node.path("acceptedAt").asText()),
                    node.path("upToDate").asBoolean());
        } catch (JsonProcessingException | RuntimeException unreadable) {
            throw new IllegalStateException("A stored terms acceptance answer could not be read", unreadable);
        }
    }

    private static String digest(String version) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(version.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    /** One acceptance request: the person of the token, the version of the body, the key and the correlation id. */
    public record Acceptance(Principal person, String version, IdempotencyKey key, String correlationId) {

        public Acceptance {
            Objects.requireNonNull(person, "person");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /** The status answered; {@code replayed} when the Idempotency-Key answered an earlier request. */
    public record Result(TermsStatus status, boolean replayed) {}
}
