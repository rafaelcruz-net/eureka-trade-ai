package com.memetrade.ledger.application;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.ledger.api.FillRecorded;
import com.memetrade.ledger.api.PaperEnums.PositionStatus;
import com.memetrade.ledger.api.PaperExecution.FillDetails;
import com.memetrade.ledger.domain.FillCalculator;
import com.memetrade.ledger.domain.FillQuote;
import com.memetrade.ledger.domain.LedgerEntries;
import com.memetrade.ledger.domain.PositionAccounting;
import com.memetrade.ledger.domain.PositionAccounting.Holding;
import com.memetrade.ledger.domain.Scales;
import com.memetrade.ledger.infra.JdbcLedgerRepository;
import com.memetrade.ledger.infra.JdbcLedgerRepository.AccountRow;
import com.memetrade.ledger.infra.JdbcLedgerRepository.PositionRow;
import com.memetrade.ledger.infra.JdbcLedgerRepository.StoredOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Writes one fill together (BR2.1): the fill with every hypothesis, the entries of BR2.2 or BR5.1, the position of
 * BR2.3, the daily summary row, the audit event and the synchronous {@link FillRecorded}, whose listener recomputes the
 * equity peak last (lock order of BR2.8). The account and the position are already locked by the caller.
 */
@Component
public class FillRecorder {

    /** What kind of fill is written. */
    public enum Kind {
        BUY,
        SELL,
        CLOSE
    }

    private final JdbcLedgerRepository repository;
    private final FillCalculator calculator;
    private final LedgerAudit audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FillRecorder(
            JdbcLedgerRepository repository,
            FillCalculator calculator,
            LedgerAudit audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.repository = repository;
        this.calculator = calculator;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param byTheSystem true for the pending close completed by the job (actor {@code system})
     */
    public FillDetails record(
            AccountRow account,
            StoredOrder order,
            PositionRow position,
            FillQuote.Computed quote,
            Kind kind,
            boolean byTheSystem) {
        Instant now = clock.instant();
        Holding before = new Holding(position.quantity(), position.costBasis());
        PositionAccounting.Outcome outcome =
                switch (kind) {
                    case BUY -> PositionAccounting.buy(before, quote.filledQuantity(), quote.grossAmount());
                    case SELL -> PositionAccounting.sell(before, quote.filledQuantity(), quote.grossAmount());
                    case CLOSE -> PositionAccounting.close(before, quote.grossAmount());
                };
        FillDetails fill = repository.insertFill(
                order, quote, outcome.realized(), calculator.policy().version(), now);
        List<LedgerEntries.Draft> drafts =
                switch (kind) {
                    case BUY -> LedgerEntries.forBuy(quote);
                    case SELL -> LedgerEntries.forSell(quote);
                    case CLOSE -> LedgerEntries.forClose(quote);
                };
        for (LedgerEntries.Draft draft : drafts) {
            repository.insertEntry(
                    account.tenantId(),
                    account.ownerUserId(),
                    account.botId(),
                    draft.type(),
                    draft.amount(),
                    order.orderId().toString(),
                    null,
                    now);
        }
        Holding after = outcome.after();
        repository.updatePosition(
                position.positionId(),
                after.quantity(),
                after.costBasis(),
                after.isEmpty() ? PositionStatus.CLOSED : PositionStatus.OPEN,
                now);
        repository.addDaily(
                account,
                order.chain(),
                order.tokenMint(),
                LocalDate.ofInstant(now, ZoneOffset.UTC),
                outcome.realized().orElse(Scales.zeroMoney()),
                quote.fee(),
                now);
        publishAudit(account, order, byTheSystem, position);
        events.publishEvent(
                new FillRecorded(account.tenantId(), account.ownerUserId(), account.botId(), order.orderId(), now));
        return fill;
    }

    private void publishAudit(AccountRow account, StoredOrder order, boolean byTheSystem, PositionRow position) {
        if (byTheSystem) {
            audit.system(
                    AuditActions.POSITION_CLOSE_COMPLETED,
                    "position:" + position.positionId(),
                    Map.of(
                            "botId",
                            account.botId().toString(),
                            "orderId",
                            order.orderId().toString()));
            return;
        }
        audit.person(
                account.tenantId(),
                account.ownerUserId(),
                AuditActions.PAPER_ORDER_FILLED,
                "order:" + order.orderId(),
                Map.of("status", order.status(), "kind", order.kind()));
    }
}
