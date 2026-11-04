package com.memetrade.ledger.application;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderKind;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperEnums.OrderStatus;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.domain.FillCalculator;
import com.memetrade.ledger.domain.FillQuote;
import com.memetrade.ledger.domain.MarketInputs;
import com.memetrade.ledger.infra.JdbcLedgerRepository;
import com.memetrade.ledger.infra.JdbcLedgerRepository.AccountRow;
import com.memetrade.ledger.infra.JdbcLedgerRepository.NewOrder;
import com.memetrade.ledger.infra.JdbcLedgerRepository.PositionRow;
import com.memetrade.ledger.infra.JdbcLedgerRepository.StoredOrder;
import com.memetrade.ledger.infra.LedgerProperties;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C15 (F2 here; F1 in {@link FundingService}; F3 in {@link ClosingService}). Every call joins the cycle transaction of
 * U5 ({@code MANDATORY}, BR2.1), sets its lock and statement timeouts with {@code SET LOCAL} (NFR10.8), locks the
 * account first (BR2.8) and checks the idempotency of the key BEFORE the state of the account (BR2.7, BR3.2). The
 * result is computed first and the order is inserted ONCE with its final status (finding of the design review).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PaperExecutionImpl implements PaperExecution {

    private final JdbcLedgerRepository repository;
    private final FillCalculator calculator;
    private final FillRecorder recorder;
    private final MarketReader market;
    private final AuthContext auth;
    private final LedgerAudit audit;
    private final LedgerProperties properties;
    private final FundingService funding;
    private final ClosingService closing;
    private final Clock clock;

    public PaperExecutionImpl(
            JdbcLedgerRepository repository,
            FillCalculator calculator,
            FillRecorder recorder,
            MarketReader market,
            AuthContext auth,
            LedgerAudit audit,
            LedgerProperties properties,
            FundingService funding,
            ClosingService closing,
            Clock clock) {
        this.repository = repository;
        this.calculator = calculator;
        this.recorder = recorder;
        this.market = market;
        this.auth = auth;
        this.audit = audit;
        this.properties = properties;
        this.funding = funding;
        this.closing = closing;
        this.clock = clock;
    }

    @Override
    public FundResult fund(UUID botId, BigDecimal amount, String idempotencyKey) {
        return funding.fund(botId, amount, idempotencyKey);
    }

    @Override
    public CloseResult closePositions(UUID botId, long limitsVersion, String idempotencyKey) {
        return closing.closePositions(botId, limitsVersion, idempotencyKey);
    }

    @Override
    public ExecutionResult execute(PaperOrderRequest order, String idempotencyKey) {
        Principal person = auth.current();
        repository.setLocalTimeouts(properties.lockTimeout(), Optional.of(properties.statementTimeout()));
        AccountRow account = repository
                .lockAccount(order.botId())
                .orElseThrow(() -> new IllegalStateException(
                        "No PAPER account for bot " + order.botId() + ": U5 must call fund at the activation"));
        if (!owns(person, account)) {
            audit.tenantMismatch(person.tenantId(), person.userId(), order.botId(), "paper.execute");
            return new ExecutionResult.NotFilled(Optional.empty(), NotFilledReason.TENANT_MISMATCH);
        }
        String digest = Digests.sha256(
                order.botId(),
                order.decisionId(),
                order.side(),
                order.chain(),
                order.tokenMint(),
                order.requestedQuantity(),
                order.limitsVersion());
        Optional<StoredOrder> existing = repository.findOrder(account.tenantId(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), digest, idempotencyKey);
        }
        if (account.status() != AccountStatus.OPEN) {
            return notFilled(account, order, idempotencyKey, digest, NotFilledReason.ACCOUNT_CLOSING);
        }
        TokenId token = new TokenId(order.chain(), order.tokenMint());
        Optional<PositionRow> position = repository.findPosition(order.botId(), order.chain(), order.tokenMint());
        MarketInputs inputs = market.read(token, position.map(PositionRow::tokenDecimals), order.botId());
        FillQuote quote = calculator.quote(
                order.side(),
                order.requestedQuantity(),
                position.map(PositionRow::quantity).orElse(BigDecimal.ZERO),
                repository.balance(order.botId()),
                inputs);
        if (quote instanceof FillQuote.Refused refused) {
            return notFilled(account, order, idempotencyKey, digest, refused.reason());
        }
        return fill(account, order, idempotencyKey, digest, (FillQuote.Computed) quote);
    }

    private ExecutionResult fill(
            AccountRow account, PaperOrderRequest order, String key, String digest, FillQuote.Computed quote) {
        OrderStatus status = quote.partial() ? OrderStatus.PARTIALLY_FILLED : OrderStatus.FILLED;
        Optional<ExecutionResult> raced = insert(account, order, key, digest, status, null);
        if (raced.isPresent()) {
            return raced.get();
        }
        StoredOrder stored = repository.findOrder(account.tenantId(), key).orElseThrow();
        PositionRow position = repository.lockOrCreatePosition(
                account, order.chain(), order.tokenMint(), quote.decimals(), clock.instant());
        FillRecorder.Kind kind = order.side() == OrderSide.BUY ? FillRecorder.Kind.BUY : FillRecorder.Kind.SELL;
        FillDetails fill = recorder.record(account, stored, position, quote, kind, false);
        return new ExecutionResult.Filled(
                stored.orderId(), status, stored.requestedQuantity(), fill.filledQuantity(), fill);
    }

    private ExecutionResult notFilled(
            AccountRow account, PaperOrderRequest order, String key, String digest, NotFilledReason reason) {
        Optional<ExecutionResult> raced = insert(account, order, key, digest, OrderStatus.NOT_FILLED, reason);
        if (raced.isPresent()) {
            return raced.get();
        }
        UUID orderId =
                repository.findOrder(account.tenantId(), key).orElseThrow().orderId();
        audit.person(
                account.tenantId(),
                account.ownerUserId(),
                AuditActions.PAPER_ORDER_NOT_FILLED,
                "order:" + orderId,
                Map.of("reason", reason.name()));
        return new ExecutionResult.NotFilled(Optional.of(orderId), reason);
    }

    /**
     * Inserts the order once; a concurrent insert of the same key (another bot of the tenant) waits for it and then
     * finds the row: the result is Duplicate or Conflict and nothing else is written.
     */
    private Optional<ExecutionResult> insert(
            AccountRow account,
            PaperOrderRequest order,
            String key,
            String digest,
            OrderStatus status,
            NotFilledReason reason) {
        boolean inserted = repository.insertOrderIfAbsent(new NewOrder(
                UUID.randomUUID(),
                account.tenantId(),
                account.ownerUserId(),
                account.botId(),
                order.decisionId(),
                key,
                digest,
                OrderKind.TRADE.name(),
                order.side().name(),
                order.chain(),
                order.tokenMint(),
                order.requestedQuantity(),
                order.limitsVersion(),
                status.name(),
                reason == null ? null : reason.name(),
                clock.instant()));
        if (inserted) {
            return Optional.empty();
        }
        return Optional.of(replay(repository.findOrder(account.tenantId(), key).orElseThrow(), digest, key));
    }

    /** BR3.2 and BR3.3: the recorded result of the first execution, or Conflict; no new row and no new event. */
    private ExecutionResult replay(StoredOrder stored, String digest, String key) {
        if (!stored.contentDigest().equals(digest)) {
            return new ExecutionResult.Conflict(key);
        }
        OrderStatus status = OrderStatus.valueOf(stored.status());
        if (status == OrderStatus.NOT_FILLED) {
            return new ExecutionResult.Duplicate(new ExecutionResult.NotFilled(
                    Optional.of(stored.orderId()),
                    NotFilledReason.valueOf(stored.notFilledReason().orElseThrow())));
        }
        FillDetails fill = repository
                .fill(stored.orderId())
                .orElseThrow(() -> new IllegalStateException("Filled order without fill: " + stored.orderId()));
        return new ExecutionResult.Duplicate(new ExecutionResult.Filled(
                stored.orderId(), status, stored.requestedQuantity(), fill.filledQuantity(), fill));
    }

    static boolean owns(Principal person, AccountRow account) {
        return person.tenantId().equals(account.tenantId()) && person.userId().equals(account.ownerUserId());
    }
}
