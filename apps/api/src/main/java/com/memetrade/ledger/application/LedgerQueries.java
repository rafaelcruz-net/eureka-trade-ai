package com.memetrade.ledger.application;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.AccountView;
import com.memetrade.ledger.api.LedgerReadModel.EntryKey;
import com.memetrade.ledger.api.LedgerReadModel.EntryPage;
import com.memetrade.ledger.api.LedgerReadModel.FillView;
import com.memetrade.ledger.api.LedgerReadModel.TradeKey;
import com.memetrade.ledger.api.LedgerReadModel.TradePage;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of the routes of {@code ledger.web} (F8, BR7.5, BR9.1): the person comes from the token; a bot of
 * another person or tenant is {@code 404} without any data; a bot without PAPER account (not activated) is empty.
 */
@Service
@Transactional(readOnly = true)
public class LedgerQueries {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);

    private final LedgerReadModel ledger;
    private final Clock clock;

    public LedgerQueries(LedgerReadModel ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Exposure by cost, loss of the current UTC day and the day (DayUsage without the limits of U5). */
    public record DayUsage(BigDecimal exposure, BigDecimal dayLoss, LocalDate utcDay) {}

    public TradePage trades(Principal person, UUID botId, Optional<TradeKey> after, int limit) {
        if (!visible(person, botId)) {
            return new TradePage(List.of(), Optional.empty(), 0);
        }
        return ledger.trades(person.tenantId(), person.userId(), botId, after, limit);
    }

    public EntryPage entries(Principal person, Optional<UUID> botId, Optional<EntryKey> after, int limit) {
        if (botId.isPresent() && !visible(person, botId.get())) {
            return new EntryPage(List.of(), Optional.empty(), 0);
        }
        return ledger.entries(person.tenantId(), person.userId(), botId, after, limit);
    }

    public DayUsage dayUsage(Principal person, UUID botId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!visible(person, botId)) {
            return new DayUsage(ZERO, ZERO, today);
        }
        BigDecimal exposure = ledger.positions(botId).stream()
                .filter(p -> p.quantity().signum() > 0)
                .map(p -> p.costBasis())
                .reduce(ZERO, BigDecimal::add);
        var start = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<FillView> fills = ledger.fills(
                person.tenantId(),
                person.userId(),
                Optional.of(botId),
                start,
                start.plus(java.time.Duration.ofDays(1)));
        BigDecimal result = ZERO;
        for (FillView fill : fills) {
            result = result.add(fill.realized()).subtract(fill.fees());
        }
        return new DayUsage(exposure, result.signum() < 0 ? result.negate() : ZERO, today);
    }

    /** BR9.1: false without PAPER account; {@code 404} when the account is of another person or tenant. */
    public boolean visible(Principal person, UUID botId) {
        Optional<AccountView> account = ledger.account(botId);
        if (account.isEmpty()) {
            return false;
        }
        if (!account.get().tenantId().equals(person.tenantId())
                || !account.get().ownerUserId().equals(person.userId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Bot not found.");
        }
        return true;
    }
}
