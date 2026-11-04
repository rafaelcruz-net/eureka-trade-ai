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
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderKind;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperEnums.OrderStatus;
import com.memetrade.ledger.api.PaperEnums.PositionStatus;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.api.PaperExecution.FillDetails;
import com.memetrade.ledger.domain.FillCalculator;
import com.memetrade.ledger.domain.FillQuote;
import com.memetrade.ledger.infra.JdbcLedgerRepository;
import com.memetrade.ledger.infra.JdbcLedgerRepository.AccountRow;
import com.memetrade.ledger.infra.JdbcLedgerRepository.NewOrder;
import com.memetrade.ledger.infra.JdbcLedgerRepository.PositionRow;
import com.memetrade.ledger.infra.JdbcLedgerRepository.StoredOrder;
import com.memetrade.ledger.infra.LedgerProperties;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F3 (BR5.1, BR5.2) through the common idempotency mechanism (operation {@code PAPER_CLOSE_POSITIONS}) inside the
 * closing transaction of U5, and F4 for the pending close job (BR5.3). Locks the account, then the positions by
 * (chain, mint) (BR2.8). Each CLOSE order has the derived key {@code <key>:<positionId>} and the limits version of the
 * closing (BR3.1, BR3.4). With the account already CLOSING or CLOSED, a CLOSE_PENDING position gets no new order and
 * the current state is returned (finding of the design review).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ClosingService {

    static final int CLOSED = 200;
    static final int REFUSED = 403;

    /**
     * R-05 of the code review: the derived key {@code <key>:<positionId>} adds one colon and a 36-character UUID and must
     * fit {@code paper_order.idempotency_key varchar(200)}, so the closing key has at most 200 - 37 = 163 characters.
     */
    static final int MAX_KEY_LENGTH = 200 - 1 - 36;

    private final JdbcLedgerRepository repository;
    private final FillCalculator calculator;
    private final FillRecorder recorder;
    private final MarketReader market;
    private final IdempotencyGuard idempotency;
    private final AuthContext auth;
    private final LedgerAudit audit;
    private final LedgerProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public ClosingService(
            JdbcLedgerRepository repository,
            FillCalculator calculator,
            FillRecorder recorder,
            MarketReader market,
            IdempotencyGuard idempotency,
            AuthContext auth,
            LedgerAudit audit,
            LedgerProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.calculator = calculator;
        this.recorder = recorder;
        this.market = market;
        this.idempotency = idempotency;
        this.auth = auth;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public CloseResult closePositions(UUID botId, long limitsVersion, String idempotencyKey) {
        Principal person = auth.current();
        IdempotencyRequest request = new IdempotencyRequest(
                IdempotentOperation.PAPER_CLOSE_POSITIONS,
                IdempotencyScope.authenticated(person, IdempotentOperation.PAPER_CLOSE_POSITIONS),
                Digests.key(idempotencyKey, MAX_KEY_LENGTH),
                Digests.sha256(botId, limitsVersion));
        IdempotentResponse response;
        try {
            response = idempotency.execute(
                    request, () -> IdempotentOutcome.of(close(person, botId, limitsVersion, idempotencyKey)));
        } catch (ApiException conflict) {
            if (conflict.code() != ErrorCode.IDEMPOTENCY_CONFLICT) {
                throw conflict;
            }
            return new CloseResult.Conflict("DIFFERENT_CONTENT");
        }
        if (response.status() == REFUSED) {
            return new CloseResult.Refused(NotFilledReason.TENANT_MISMATCH);
        }
        CloseResult.Closed stored = read(response.body());
        return new CloseResult.Closed(
                stored.botId(), stored.accountStatus(), stored.closed(), stored.pending(), response.replayed());
    }

    /**
     * F4 for one pending position, in the job's own transaction (BR5.3): locks the account and then the positions
     * (BR2.8) with the job lock timeout, re-checks CLOSE_PENDING under the lock, closes at the reference price, moves
     * the CLOSE order to FILLED, audits {@code POSITION_CLOSE_COMPLETED} with actor {@code system} and closes the
     * account when nothing is left open. Tenant and person come from the row itself (BR9.1).
     *
     * @return true when this call completed the close; false when it was already done or the price is not reliable
     */
    public boolean completePending(UUID botId, UUID positionId) {
        repository.setLocalTimeouts(properties.jobLockTimeout(), Optional.empty());
        AccountRow account = repository
                .lockAccount(botId)
                .orElseThrow(() -> new IllegalStateException("No PAPER account for bot " + botId));
        List<PositionRow> positions = repository.lockPositions(botId);
        Optional<PositionRow> pending = positions.stream()
                .filter(p -> p.positionId().equals(positionId) && p.status() == PositionStatus.CLOSE_PENDING)
                .findFirst();
        if (pending.isEmpty()) {
            return false;
        }
        PositionRow position = pending.get();
        FillQuote quote = calculator.closeQuote(
                position.quantity(),
                market.read(
                        new TokenId(position.chain(), position.tokenMint()),
                        Optional.of(position.tokenDecimals()),
                        botId));
        if (!(quote instanceof FillQuote.Computed computed)) {
            return false;
        }
        StoredOrder order = repository
                .pendingCloseOrder(position)
                .orElseThrow(() -> new IllegalStateException("Pending position without CLOSE order: " + positionId));
        repository.markCloseOrderFilled(order.orderId());
        recorder.record(account, order, position, computed, FillRecorder.Kind.CLOSE, true);
        boolean anyOpen = positions.stream()
                .anyMatch(
                        p -> !p.positionId().equals(positionId) && p.quantity().signum() > 0);
        if (!anyOpen && account.status() == AccountStatus.CLOSING) {
            repository.updateAccountStatus(botId, AccountStatus.CLOSED);
        }
        return true;
    }

    private IdempotentResponse close(Principal person, UUID botId, long limitsVersion, String key) {
        repository.setLocalTimeouts(properties.lockTimeout(), Optional.of(properties.statementTimeout()));
        AccountRow account = repository
                .lockAccount(botId)
                .orElseThrow(() -> new IllegalStateException("No PAPER account for bot " + botId));
        if (!PaperExecutionImpl.owns(person, account)) {
            audit.tenantMismatch(person.tenantId(), person.userId(), botId, "paper.close-positions");
            return IdempotentResponse.fresh(REFUSED, "{\"refused\":\"TENANT_MISMATCH\"}");
        }
        List<PositionRow> positions = repository.lockPositions(botId);
        AccountStatus status = account.status();
        if (status == AccountStatus.OPEN) {
            repository.updateAccountStatus(botId, AccountStatus.CLOSING);
            status = AccountStatus.CLOSING;
        }
        List<CloseResult.ClosedPosition> closed = new ArrayList<>();
        List<CloseResult.PendingPosition> pending = new ArrayList<>();
        for (PositionRow position : positions) {
            if (position.quantity().signum() == 0) {
                continue;
            }
            if (position.status() == PositionStatus.CLOSE_PENDING) {
                StoredOrder order = repository
                        .pendingCloseOrder(position)
                        .orElseThrow(() -> new IllegalStateException(
                                "Pending position without CLOSE order: " + position.positionId()));
                pending.add(pendingOf(position, order.orderId()));
                continue;
            }
            closeOne(account, position, limitsVersion, key, closed, pending);
        }
        if (pending.isEmpty() && status == AccountStatus.CLOSING) {
            repository.updateAccountStatus(botId, AccountStatus.CLOSED);
            status = AccountStatus.CLOSED;
        }
        return IdempotentResponse.fresh(CLOSED, write(new CloseResult.Closed(botId, status, closed, pending, false)));
    }

    private void closeOne(
            AccountRow account,
            PositionRow position,
            long limitsVersion,
            String key,
            List<CloseResult.ClosedPosition> closed,
            List<CloseResult.PendingPosition> pending) {
        FillQuote quote = calculator.closeQuote(
                position.quantity(),
                market.read(
                        new TokenId(position.chain(), position.tokenMint()),
                        Optional.of(position.tokenDecimals()),
                        account.botId()));
        OrderStatus orderStatus = quote instanceof FillQuote.Computed ? OrderStatus.FILLED : OrderStatus.CLOSE_PENDING;
        String derivedKey = key + ":" + position.positionId();
        insertClose(account, position, limitsVersion, derivedKey, orderStatus);
        StoredOrder order = repository.findOrder(account.tenantId(), derivedKey).orElseThrow();
        if (quote instanceof FillQuote.Computed computed) {
            FillDetails fill = recorder.record(account, order, position, computed, FillRecorder.Kind.CLOSE, false);
            closed.add(new CloseResult.ClosedPosition(
                    position.positionId(),
                    order.orderId(),
                    position.chain(),
                    position.tokenMint(),
                    position.quantity(),
                    fill.realized().orElseThrow()));
            return;
        }
        repository.updatePosition(
                position.positionId(),
                position.quantity(),
                position.costBasis(),
                PositionStatus.CLOSE_PENDING,
                clock.instant());
        audit.person(
                account.tenantId(),
                account.ownerUserId(),
                AuditActions.POSITION_CLOSE_PENDING,
                "position:" + position.positionId(),
                Map.of("orderId", order.orderId().toString()));
        pending.add(pendingOf(position, order.orderId()));
    }

    private void insertClose(
            AccountRow account, PositionRow position, long limitsVersion, String derivedKey, OrderStatus status) {
        boolean inserted = repository.insertOrderIfAbsent(new NewOrder(
                UUID.randomUUID(),
                account.tenantId(),
                account.ownerUserId(),
                account.botId(),
                null,
                derivedKey,
                Digests.sha256(
                        account.botId(), OrderKind.CLOSE, position.positionId(), position.quantity(), limitsVersion),
                OrderKind.CLOSE.name(),
                OrderSide.SELL.name(),
                position.chain(),
                position.tokenMint(),
                position.quantity(),
                limitsVersion,
                status.name(),
                null,
                clock.instant()));
        if (!inserted) {
            throw new IllegalStateException(
                    "The derived CLOSE key already exists for position " + position.positionId());
        }
    }

    private static CloseResult.PendingPosition pendingOf(PositionRow position, UUID orderId) {
        return new CloseResult.PendingPosition(
                position.positionId(), orderId, position.chain(), position.tokenMint(), position.quantity());
    }

    private String write(CloseResult.Closed closed) {
        try {
            return json.writeValueAsString(closed);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The closing result could not be serialised", failure);
        }
    }

    private CloseResult.Closed read(String body) {
        try {
            return json.readValue(body, CloseResult.Closed.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The stored closing result could not be read", failure);
        }
    }
}
