package com.memetrade.realexits.fiscal;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.shared.web.CorrelationId;
import java.io.OutputStream;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F4, BR7: the fiscal CSV of the person of the token. The REAL ledger of every bot wallet the person had (M1) is read
 * through the M2 in a stream ({@code fetchSize} 500, read-only transaction), mapped by reference (BR7.3) and written
 * line by line; nothing is kept in memory nor stored on the server (BR7.10, U7-NFR3.3). PAPER never enters: only the
 * REAL ledger is read (BR7.8, U7-NFR2.1). No address of the person goes in the file. The export is audited with the
 * period and the count of lines, never the content; the log carries only the period and the count (U7-NFR5.11).
 */
@Service
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class FiscalExportService {

    private static final Logger log = LoggerFactory.getLogger(FiscalExportService.class);
    private static final String CHAIN = "solana";

    private final RealLedger ledger;
    private final RealStateQuery states;
    private final MarketDataQuery market;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public FiscalExportService(
            RealLedger ledger,
            RealStateQuery states,
            MarketDataQuery market,
            AuditEventPublisher audit,
            Clock clock,
            PlatformTransactionManager manager) {
        this.ledger = ledger;
        this.states = states;
        this.market = market;
        this.audit = audit;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /** Writes the CSV of the period on {@code out}; returns the number of lines after the header. */
    public long export(Principal person, FiscalPeriod period, OutputStream out) {
        List<UUID> wallets = states.botWalletIds(person.tenantId(), person.userId());
        AtomicLong rows = new AtomicLong();
        Map<String, Optional<Integer>> decimals = new HashMap<>();
        Map<String, Optional<String>> symbols = new HashMap<>();
        try (FiscalCsvWriter writer = new FiscalCsvWriter(
                out,
                period.zone(),
                mint -> decimals.computeIfAbsent(mint, this::decimalsOf),
                mint -> symbols.computeIfAbsent(mint, this::symbolOf))) {
            writer.header();
            FiscalRowMapper mapper = new FiscalRowMapper(row -> {
                writer.write(row);
                rows.incrementAndGet();
            });
            if (!wallets.isEmpty()) {
                ledger.streamEntries(person.tenantId(), wallets, period.start(), period.end(), mapper::accept);
            }
            mapper.finish();
        }
        transactions.executeWithoutResult(status -> audit.publish(AuditEventCommand.person(
                person.tenantId(),
                person.userId(),
                "REAL_FISCAL_EXPORTED",
                "real-fiscal-export:" + person.userId(),
                CorrelationId.current(),
                clock.instant(),
                Map.of(
                        "from", period.from().toString(),
                        "to", period.to().toString(),
                        "rows", Long.toString(rows.get())))));
        log.info("REAL fiscal export: from={}, to={}, rows={}", period.from(), period.to(), rows.get());
        return rows.get();
    }

    private Optional<Integer> decimalsOf(String mint) {
        MarketValue value = market.decimals(new TokenId(CHAIN, mint));
        return value instanceof MarketValue.Known known
                ? Optional.of(known.value().intValueExact())
                : Optional.empty();
    }

    private Optional<String> symbolOf(String mint) {
        TokenId token = new TokenId(CHAIN, mint);
        return Optional.ofNullable(market.symbolsOf(Set.of(token)).get(token));
    }
}
