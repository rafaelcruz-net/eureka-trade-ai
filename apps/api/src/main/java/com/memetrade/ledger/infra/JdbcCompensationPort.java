package com.memetrade.ledger.infra;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.application.LedgerAudit;
import com.memetrade.ledger.domain.CompensationPort;
import com.memetrade.ledger.infra.JdbcLedgerRepository.EntryRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of the compensation mechanism (F10): the COMPENSATION entry referencing the original, the fee correction
 * of the daily summary row of the ORIGINAL fill's UTC day (performance-design.md: compensation, original FEE, its
 * order, the fill's {@code filled_at}) and {@code LEDGER_ENTRY_COMPENSATED}, all in the caller's transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcCompensationPort implements CompensationPort {

    private final JdbcLedgerRepository repository;
    private final JdbcClient jdbc;
    private final LedgerAudit audit;
    private final Clock clock;

    public JdbcCompensationPort(JdbcLedgerRepository repository, JdbcClient jdbc, LedgerAudit audit, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public Optional<OriginalEntry> find(UUID entryId) {
        return repository
                .findEntry(entryId)
                .map(row -> new OriginalEntry(row.entryId(), row.botId(), row.type(), row.amount(), row.referenceId()));
    }

    @Override
    public boolean alreadyCompensated(UUID entryId) {
        return repository.isCompensated(entryId);
    }

    @Override
    public UUID record(OriginalEntry original, BigDecimal amount, String reason) {
        EntryRow row = repository.findEntry(original.entryId()).orElseThrow();
        Instant now = clock.instant();
        UUID compensation = repository.insertEntry(
                row.tenantId(),
                row.ownerUserId(),
                row.botId(),
                EntryType.COMPENSATION,
                amount,
                row.entryId().toString(),
                row.entryId(),
                now);
        if (row.type() == EntryType.FEE) {
            Map<String, Object> fill = jdbc.sql(
                            "select chain, token_mint, filled_at from fill where order_id::text = :o")
                    .param("o", row.referenceId())
                    .query()
                    .singleRow();
            repository.addDailyFees(
                    row.botId(),
                    (String) fill.get("chain"),
                    (String) fill.get("token_mint"),
                    LocalDate.ofInstant(((Timestamp) fill.get("filled_at")).toInstant(), ZoneOffset.UTC),
                    amount.negate(),
                    now);
        }
        audit.person(
                row.tenantId(),
                row.ownerUserId(),
                AuditActions.LEDGER_ENTRY_COMPENSATED,
                "entry:" + row.entryId(),
                Map.of("reason", reason, "compensationId", compensation.toString()));
        return compensation;
    }
}
