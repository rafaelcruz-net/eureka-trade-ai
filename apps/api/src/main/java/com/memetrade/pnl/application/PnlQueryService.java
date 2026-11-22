package com.memetrade.pnl.application;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.AccountView;
import com.memetrade.ledger.api.LedgerReadModel.DailySummaryRow;
import com.memetrade.ledger.api.LedgerReadModel.FillView;
import com.memetrade.ledger.api.LedgerReadModel.PositionView;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.domain.ExitValuation;
import com.memetrade.pnl.domain.Mark;
import com.memetrade.pnl.domain.PnlCalculator;
import com.memetrade.pnl.domain.PnlCalculator.TokenInput;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F7, the P&L of a period (BR6.1 to BR6.6). Whole UTC days inside [from, to) come from the daily summary and the
 * partial first and last days straight from the fills (performance-design.md); the unrealized is of the positions open
 * NOW by the exit price. Read only, in one REPEATABLE READ snapshot, filtered by tenant, person and PAPER. A bot of
 * another person is {@code 404}; a bot without PAPER account (not activated) gives an empty report.
 */
@Service
public class PnlQueryService {

    public static final Duration MAX_PERIOD = Duration.ofDays(366);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);

    private final LedgerReadModel ledger;
    private final PortfolioMarks marks;
    private final MarketDataQuery market;
    private final Clock clock;

    public PnlQueryService(LedgerReadModel ledger, PortfolioMarks marks, MarketDataQuery market, Clock clock) {
        this.ledger = ledger;
        this.marks = marks;
        this.market = market;
        this.clock = clock;
    }

    /**
     * @param marks the current reference price of each line (KNOWN with source and time, or UNKNOWN), by
     *     {@code chain|mint}
     */
    public record PnlView(
            Instant from,
            Instant to,
            PnlCalculator.Report report,
            Map<String, MarketValue> marks,
            Instant unrealizedAsOf) {

        public MarketValue mark(String chain, String mint) {
            return marks.get(chain + "|" + mint);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PnlView report(Principal person, Instant from, Instant to, Optional<UUID> botId) {
        validate(from, to);
        return reportSince(person, from, to, botId);
    }

    /**
     * 261121-paper-real-unico U1 (BR5.9, performance-design.md "Bloco PAPER"): the SAME calculation without the limit
     * of 366 days of the public route, for the window ALL of the P&L by mode. Same snapshot rules: it takes part in the
     * REPEATABLE READ transaction of the caller (the PAPER block of C4).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PnlView reportSince(Principal person, Instant from, Instant to, Optional<UUID> botId) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (botId.isPresent() && !visible(person, botId.get())) {
            return new PnlView(from, to, PnlCalculator.report(List.of()), Map.of(), clock.instant());
        }
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
        for (Period period : periods(from, to)) {
            if (period.summary()) {
                for (DailySummaryRow row : ledger.dailySummary(
                        person.tenantId(), person.userId(), botId, day(period.from()), day(period.to()))) {
                    add(totals, row.chain(), row.tokenMint(), row.realized(), row.fees());
                }
            } else {
                for (FillView fill :
                        ledger.fills(person.tenantId(), person.userId(), botId, period.from(), period.to())) {
                    add(totals, fill.chain(), fill.tokenMint(), fill.realized(), fill.fees());
                }
            }
        }
        Map<String, List<PositionView>> open = new LinkedHashMap<>();
        for (PositionView position : ledger.openPositions(person.tenantId(), person.userId(), botId)) {
            String key = key(position.chain(), position.tokenMint());
            open.computeIfAbsent(key, k -> new ArrayList<>()).add(position);
            totals.computeIfAbsent(key, k -> new BigDecimal[] {ZERO, ZERO});
        }
        ExitValuation valuation = marks.valuation();
        List<TokenInput> inputs = new ArrayList<>();
        Map<String, MarketValue> prices = new LinkedHashMap<>();
        totals.forEach((key, sums) -> {
            String[] token = key.split("\\|", 2);
            Optional<Mark> mark = Optional.ofNullable(open.get(key)).map(positions -> combined(valuation, positions));
            inputs.add(new TokenInput(token[0], token[1], sums[0], sums[1], mark));
            prices.put(key, market.referencePrice(new TokenId(token[0], token[1])));
        });
        return new PnlView(from, to, PnlCalculator.report(inputs), prices, clock.instant());
    }

    /** BR6.1: from before to, at most 366 days. */
    static void validate(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw ApiException.validation("from", "from must be before to.");
        }
        if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw ApiException.validation("to", "The period has at most 366 days.");
        }
    }

    /**
     * BR9.1: true when the bot is the person's; false when it has no PAPER account (empty report); a bot of another
     * person or tenant is {@code 404} without any data.
     */
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

    /** One open position per bot of the same token: unknown if any is unknown, otherwise the sum. */
    private Mark combined(ExitValuation valuation, List<PositionView> positions) {
        List<Mark> each = positions.stream().map(p -> marks.mark(valuation, p)).toList();
        if (each.size() == 1) {
            return each.getFirst();
        }
        BigDecimal unrealized = ZERO;
        BigDecimal net = ZERO;
        for (Mark mark : each) {
            if (mark instanceof Mark.Unvalued unvalued) {
                return unvalued;
            }
            unrealized = unrealized.add(((Mark.Valued) mark).unrealized());
            net = net.add(((Mark.Valued) mark).netExitValue());
        }
        return new Mark.Valued(BigDecimal.ZERO, BigDecimal.ZERO, net, BigDecimal.ZERO, net, unrealized);
    }

    /** The parts of [from, to): partial days read from the fills, whole UTC days from the summary. */
    static List<Period> periods(Instant from, Instant to) {
        Instant firstWhole =
                startOfDay(from).equals(from) ? from : startOfDay(from).plus(Duration.ofDays(1));
        Instant lastWhole = startOfDay(to);
        List<Period> periods = new ArrayList<>();
        if (!firstWhole.isBefore(lastWhole)) {
            periods.add(new Period(from, to, false));
            return periods;
        }
        if (from.isBefore(firstWhole)) {
            periods.add(new Period(from, firstWhole, false));
        }
        periods.add(new Period(firstWhole, lastWhole, true));
        if (lastWhole.isBefore(to)) {
            periods.add(new Period(lastWhole, to, false));
        }
        return periods;
    }

    record Period(Instant from, Instant to, boolean summary) {}

    private static Instant startOfDay(Instant instant) {
        return day(instant).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate day(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void add(
            Map<String, BigDecimal[]> totals, String chain, String mint, BigDecimal realized, BigDecimal fees) {
        BigDecimal[] sums = totals.computeIfAbsent(key(chain, mint), k -> new BigDecimal[] {ZERO, ZERO});
        sums[0] = sums[0].add(realized);
        sums[1] = sums[1].add(fees);
    }

    private static String key(String chain, String mint) {
        return chain + "|" + mint;
    }
}
