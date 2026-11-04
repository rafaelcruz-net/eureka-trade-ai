package com.memetrade.ledger.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperAccountFunded;
import com.memetrade.ledger.api.PaperExecution.FundResult;
import com.memetrade.ledger.domain.LedgerEntries;
import com.memetrade.ledger.infra.JdbcLedgerRepository;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F1, the initial virtual balance (BR2.6), through the common idempotency mechanism of the identity module (operation
 * {@code PAPER_FUND}, scope tenant + person), inside the activation transaction of U5. The partial unique index of
 * FUNDING per bot is the final guarantee.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class FundingService {

    static final int FUNDED = 201;
    static final int ALREADY_FUNDED = 409;

    private final JdbcLedgerRepository repository;
    private final IdempotencyGuard idempotency;
    private final AuthContext auth;
    private final LedgerAudit audit;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;

    public FundingService(
            JdbcLedgerRepository repository,
            IdempotencyGuard idempotency,
            AuthContext auth,
            LedgerAudit audit,
            ApplicationEventPublisher events,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.auth = auth;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    public FundResult fund(UUID botId, BigDecimal amount, String idempotencyKey) {
        Principal person = auth.current();
        LedgerEntries.Draft funding = LedgerEntries.forFunding(amount);
        IdempotencyRequest request = new IdempotencyRequest(
                IdempotentOperation.PAPER_FUND,
                IdempotencyScope.authenticated(person, IdempotentOperation.PAPER_FUND),
                Digests.key(idempotencyKey),
                Digests.sha256(botId, funding.amount()));
        IdempotentResponse response;
        try {
            response = idempotency.execute(
                    request, () -> IdempotentOutcome.of(create(person, botId, funding, idempotencyKey)));
        } catch (ApiException conflict) {
            if (conflict.code() != ErrorCode.IDEMPOTENCY_CONFLICT) {
                throw conflict;
            }
            return new FundResult.Conflict("DIFFERENT_CONTENT");
        }
        if (response.status() == ALREADY_FUNDED) {
            return new FundResult.Conflict("ALREADY_FUNDED");
        }
        FundResult.Funded stored = read(response.body());
        return new FundResult.Funded(stored.botId(), stored.amount(), stored.entryId(), response.replayed());
    }

    private IdempotentResponse create(Principal person, UUID botId, LedgerEntries.Draft funding, String key) {
        if (repository.findAccount(botId).isPresent()) {
            return IdempotentResponse.fresh(ALREADY_FUNDED, "{\"conflict\":\"ALREADY_FUNDED\"}");
        }
        Instant now = clock.instant();
        repository.insertAccount(person, botId, now);
        UUID entryId = repository.insertEntry(person, botId, funding.type(), funding.amount(), key, null, now);
        events.publishEvent(new PaperAccountFunded(person.tenantId(), person.userId(), botId, funding.amount(), now));
        audit.person(
                person.tenantId(),
                person.userId(),
                AuditActions.PAPER_ACCOUNT_FUNDED,
                "bot:" + botId,
                Map.of("entryId", entryId.toString()));
        return IdempotentResponse.fresh(FUNDED, write(new FundResult.Funded(botId, funding.amount(), entryId, false)));
    }

    private String write(FundResult.Funded funded) {
        try {
            return json.writeValueAsString(funded);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The FUNDING result could not be serialised", failure);
        }
    }

    private FundResult.Funded read(String body) {
        try {
            return json.readValue(body, FundResult.Funded.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The stored FUNDING result could not be read", failure);
        }
    }
}
