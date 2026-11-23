package com.memetrade.ledger.infra;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.ledger.api.RecordSlotResult;
import com.memetrade.ledger.api.SlotWriter;
import com.memetrade.ledger.domain.RealBalances;
import com.memetrade.ledger.domain.RealEntryRules;
import com.memetrade.ledger.domain.RealLedgerRow;
import com.memetrade.shared.web.CorrelationId;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter of M2 over {@code real_ledger_entry}: inserts with {@code ON CONFLICT (idempotency_key) DO NOTHING} in
 * the caller's transaction; the balance groups the entries by kind and reference over the covering index. Every query
 * filters by tenant and wallet (BR6.4); the table only ever holds REAL rows (BR6.3).
 */
@Repository
public class JdbcRealLedger implements RealLedger {

    /** U7 (U7-NFR3.3): the rows of the fiscal export reach the writer 500 at a time. */
    public static final int STREAM_FETCH_SIZE = 500;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JdbcRealLedger.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;
    private final AuditEventPublisher audit;
    private final LedgerRealSlotMetrics slotMetrics;

    public JdbcRealLedger(
            JdbcClient jdbc,
            Clock clock,
            javax.sql.DataSource dataSource,
            AuditEventPublisher audit,
            LedgerRealSlotMetrics slotMetrics) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.slotMetrics = slotMetrics;
        org.springframework.jdbc.core.JdbcTemplate template =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        template.setFetchSize(STREAM_FETCH_SIZE);
        this.named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(template);
    }

    /**
     * 261121-paper-real-unico U2 (C13, C14, BR1.4, reliability-design.md "Conflito de slot"): in the caller's
     * transaction. The {@code ON CONFLICT DO NOTHING} never raises the duplicate key that would abort the transaction;
     * without a row inserted, the recorded slot is read by the primary key. A different slot is refused: the event
     * REAL_SLOT_CONFLICT goes to the outbox in the same transaction, the metric counts it after the commit and a WARN
     * line names the writer and the two slots with the signature abbreviated. Nothing here throws for a conflict.
     */
    @Override
    public RecordSlotResult recordSlot(UUID tenantId, String txSignature, long slot, SlotWriter writer) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(writer, "writer");
        if (txSignature == null || txSignature.isBlank()) {
            throw new IllegalArgumentException("a slot is recorded only for a signature");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("a slot is never negative");
        }
        int inserted = jdbc.sql("""
                        insert into real_entry_slot (tx_signature, slot, recorded_at)
                        values (:signature, :slot, :at)
                        on conflict (tx_signature) do nothing""")
                .param("signature", txSignature)
                .param("slot", slot)
                .param("at", Timestamp.from(clock.instant()))
                .update();
        if (inserted == 1) {
            // Etapa 14b: a slot given to a row written before this transaction changes its key; markEpochs checks it.
            writes().ifPresent(writes -> writes.slotted().add(txSignature));
            return RecordSlotResult.RECORDED;
        }
        long recorded = jdbc.sql("select slot from real_entry_slot where tx_signature = :signature")
                .param("signature", txSignature)
                .query(Long.class)
                .single();
        if (recorded == slot) {
            return RecordSlotResult.ALREADY_RECORDED;
        }
        auditConflict(tenantId, txSignature, recorded, slot, writer);
        slotMetrics.conflict(writer);
        log.warn(
                "event=REAL_SLOT_CONFLICT writer={} recordedSlot={} refusedSlot={} txSignature={}",
                writer,
                recorded,
                slot,
                RealReferences.abbreviate(txSignature));
        return RecordSlotResult.CONFLICT_REFUSED;
    }

    /**
     * 261121-paper-real-unico U2a, Etapa 14b (decision of the founder of 2026-11-22, NFR1.26): the view restricted by
     * the marks of zero, in ONE statement (one snapshot, review R-01). Public only for the plan test
     * (RealLedgerNetworkOrderPlanIT).
     *
     * <ol>
     *   <li>{@code marks}: the latest mark of each mint of the wallet; its window is lowered to {@code :since} (the
     *       earliest row the writer created in its transaction, null for a reader);
     *   <li>{@code summary}: ONE pass over every row of the wallet, aggregated only (no sort): the balance by kind, the
     *       count of the rows, the flows after {@code :flows} (null: none asked) and, per mint, its fills and its first
     *       fill;
     *   <li>{@code mints} and {@code bounds}: per mint, its fills and its mark; the window starts at the earliest of the
     *       first fill of every mint without a mark and the window of every mark with fills after it (a mark whose
     *       mint has no fill after it reads nothing);
     *   <li>{@code anchor} and {@code windowed}: the rows of the wallet created from that instant, and the slot of the
     *       last recorded row before them in recording order, so the inheritance of BR1.3 inside the window is the one
     *       of the whole history;
     *   <li>{@code carried}, {@code placed} and {@code flagged}: each row gets the key of the mark of its mint, and the
     *       swap fills after it (all of them without a mark, or with a full-read mark) are compared like
     *       {@link com.memetrade.ledger.api.NetworkOrderKey}; a RENT goes with a buy after the zero;
     *   <li>{@code V}: the mints whose fills are not exactly the fills of the mark plus the fills after it (a fill
     *       written without the marks, a late confirmation not marked yet): the caller reads the whole history then.
     * </ol>
     *
     * The rows meet the marks through hash aggregates and window functions, never through a join between two large
     * sets: a planner that underestimates a wallet (fresh statistics, a wallet much larger than the average, thousands
     * of mints) cannot turn it into a nested loop of rows times marks (measured 26 s against 0.4 s). The open
     * reservations come by reference, only those not closed (their net sum is not zero): the balance of RealBalances is
     * the same.
     */
    public static final String VIEW_EPOCH_SQL = """
            with marks as (
              select distinct on (m.mint) m.mint, m.zero_slot, m.zero_inherited, m.zero_tx_signature, m.zero_created_at,
                m.zero_reference, m.zero_kind_rank, m.zero_idempotency_key, m.fills_through,
                least(m.window_from, cast(:since as timestamptz)) as window_from
              from real_position_epoch m
              where m.tenant_id = :tenant and m.bot_wallet_id = :wallet
              order by m.mint, m.epoch_seq desc
            ), summary as (
              select e.entry_kind, case when e.entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT') then e.mint end as fill_mint,
                grouping(e.entry_kind, case when e.entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT') then e.mint end)
                  as level,
                sum(e.lamports) as lamports, count(*) as row_count, min(e.created_at) as first_at,
                coalesce(sum(e.lamports) filter (where e.entry_kind = 'DEPOSIT'
                  and e.created_at > cast(:flows as timestamptz)), 0) as deposited,
                coalesce(-sum(e.lamports) filter (where (e.entry_kind = 'WITHDRAWAL'
                  or (e.entry_kind = 'NETWORK_FEE' and e.reference like 'wd:%'))
                  and e.created_at > cast(:flows as timestamptz)), 0) as withdrawn
              from real_ledger_entry e
              where e.tenant_id = :tenant and e.bot_wallet_id = :wallet and e.mode = 'REAL'
              group by grouping sets ((e.entry_kind),
                (case when e.entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT') then e.mint end), ())
            ), mints as (
              select mint, sum(fills) as fills, min(first_at) as first_at, max(through) as through,
                max(mark_from) as mark_from, bool_or(marked) as marked
              from (
                select fill_mint as mint, row_count as fills, first_at, null::bigint as through,
                  null::timestamptz as mark_from, false as marked
                from summary where level = 2 and fill_mint is not null
                union all
                select mint, 0, null, fills_through, window_from, true from marks
              ) both_sides
              group by mint
            ), bounds as (
              select coalesce(min(case when not marked then first_at when fills > through then mark_from end),
                'infinity'::timestamptz) as window_from
              from mints
            ), anchor as (
              select s.slot
              from real_ledger_entry e
              join real_entry_slot s on s.tx_signature = e.tx_signature
              where e.tenant_id = :tenant and e.bot_wallet_id = :wallet and e.mode = 'REAL'
                and e.created_at < (select window_from from bounds)
              order by e.created_at desc, e.reference collate "C" desc,
                case e.entry_kind when 'SWAP_FILL_IN' then 0 when 'SWAP_FILL_OUT' then 0 when 'NETWORK_FEE' then 1
                  when 'RENT' then 2 else 3 end desc, e.idempotency_key collate "C" desc
              limit 1
            ), windowed as (
              select e.entry_kind, e.lamports, e.mint, e.token_base_units, e.tx_signature, e.reference, e.idempotency_key,
                e.created_at, s.slot as recorded_slot,
                case e.entry_kind when 'SWAP_FILL_IN' then 0 when 'SWAP_FILL_OUT' then 0 when 'NETWORK_FEE' then 1
                  when 'RENT' then 2 else 3 end as kind_rank
              from real_ledger_entry e
              left join real_entry_slot s on s.tx_signature = e.tx_signature
              where e.tenant_id = :tenant and e.bot_wallet_id = :wallet and e.mode = 'REAL'
                and e.created_at >= (select window_from from bounds)
            ), grouped as (
              select windowed.*, count(recorded_slot) over (
                order by created_at, reference collate "C", kind_rank, idempotency_key collate "C"
                rows between unbounded preceding and current row) as slot_group
              from windowed
            ), keyed as (
              select grouped.*, coalesce(recorded_slot, max(recorded_slot) over (partition by slot_group),
                (select slot from anchor), 0) as effective_slot
              from grouped
            ), carried as (
              select rows_and_marks.*,
                bool_or(is_mark) over by_mint as has_mark,
                max(m_slot) over by_mint as k_slot,
                bool_or(m_inherited) over by_mint as k_inherited,
                max(m_signature) over by_mint as k_signature,
                max(m_created_at) over by_mint as k_created_at,
                max(m_reference) over by_mint as k_reference,
                max(m_kind_rank) over by_mint as k_kind_rank,
                max(m_idempotency_key) over by_mint as k_idempotency_key
              from (
                select entry_kind, lamports, mint, token_base_units, tx_signature, reference, idempotency_key,
                  created_at, recorded_slot, kind_rank, effective_slot, false as is_mark, null::bigint as m_slot,
                  null::boolean as m_inherited, null::varchar as m_signature, null::timestamptz as m_created_at,
                  null::varchar as m_reference, null::int as m_kind_rank, null::varchar as m_idempotency_key
                from keyed
                union all
                select null, null, mint, null, null, null, null, null, null, null, null, true, zero_slot,
                  zero_inherited, zero_tx_signature, zero_created_at, zero_reference, zero_kind_rank,
                  zero_idempotency_key
                from marks
              ) rows_and_marks
              window by_mint as (partition by mint)
            ), placed as (
              select carried.*,
                entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT')
                  and (not has_mark or k_slot is null
                    or row(effective_slot, recorded_slot is null,
                        coalesce(case when recorded_slot is not null then tx_signature end, '') collate "C",
                        created_at, reference collate "C", kind_rank, idempotency_key collate "C")
                      > row(k_slot, k_inherited,
                        coalesce(case when not k_inherited then k_signature end, '') collate "C",
                        k_created_at, k_reference collate "C", k_kind_rank, k_idempotency_key collate "C"))
                  as after_zero
              from carried
              where not is_mark
            ), flagged as (
              select placed.*,
                bool_or(after_zero and entry_kind = 'SWAP_FILL_IN') over (partition by reference) as buy_after_zero
              from placed
            ), parts as (
              select 'B' as part, entry_kind, null::varchar as reference, lamports::numeric as amount,
                null::numeric as other, null::varchar as mint, null::bigint as effective_slot, null::boolean as inherited,
                null::varchar collate "C" as signature_key, null::timestamptz as created_at,
                null::varchar collate "C" as reference_key, null::int as kind_rank,
                null::varchar collate "C" as idempotency_key
              from summary where level = 1 and entry_kind not in ('RESERVE', 'RESERVE_RELEASE')
              union all
              select 'B', case when net > 0 then 'RESERVE' else 'RESERVE_RELEASE' end, reference, abs(net), null, null,
                null, null, null, null, null, null, null
              from (
                select e.reference, sum(case e.entry_kind when 'RESERVE' then e.lamports else -e.lamports end) as net
                from real_ledger_entry e
                where e.tenant_id = :tenant and e.bot_wallet_id = :wallet and e.mode = 'REAL'
                  and e.entry_kind in ('RESERVE', 'RESERVE_RELEASE')
                group by e.reference
              ) reserves where net <> 0
              union all
              select 'C', null, null, row_count, null, null, null, null, null, null, null, null, null
              from summary where level = 3
              union all
              select 'F', null, null, deposited, withdrawn, null, null, null, null, null, null, null, null
              from summary where level = 3
              union all
              select 'V', null, null, sum(fills), sum(through), mint, null, null, null, null, null, null, null
              from (
                select mint, fills, coalesce(through, 0) as through from mints
                union all
                select mint, 0, count(*) from flagged where after_zero group by mint
              ) counted
              group by mint
              having sum(fills) <> sum(through)
              union all
              select 'P', entry_kind, reference, lamports::numeric, token_base_units, mint, effective_slot,
                recorded_slot is null,
                (case when recorded_slot is not null then tx_signature end) collate "C", created_at,
                reference collate "C", kind_rank, idempotency_key collate "C"
              from flagged
              where after_zero or (entry_kind = 'RENT' and buy_after_zero)
            )
            select * from parts
            order by part, effective_slot, inherited, signature_key, created_at, reference_key, kind_rank,
              idempotency_key""";

    /**
     * 261121-paper-real-unico U2a, Etapa 14b: in the caller's transaction, after its entries and slots, under a lock of
     * the transaction on the marks of the wallet (two writers of the same wallet mark one after the other, each seeing
     * the rows the other committed). The read restricted by the current marks, or the whole history when a mark
     * disagrees with the count of its fills, feeds RealEpochs; the plan of RealEpochMarks is inserted. Only inserts.
     */
    @Override
    public void markEpochs(UUID tenantId, UUID botWalletId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(botWalletId, "botWalletId");
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "real_position_epoch:" + botWalletId)
                .query()
                .listOfRows();
        Optional<Instant> earliest = earliestWrite(tenantId, botWalletId);
        java.util.Map<String, com.memetrade.ledger.domain.EpochMark> current = marks(tenantId, botWalletId);
        EpochRead read = readEpochs(tenantId, botWalletId, Optional.empty(), earliest);
        java.util.Set<String> invalid = read.invalid();
        if (!invalid.isEmpty()) {
            read = readWhole(tenantId, botWalletId, Optional.empty());
        }
        List<com.memetrade.ledger.domain.EpochMark> plan = com.memetrade.ledger.domain.RealEpochMarks.plan(
                current, read.epochs(), invalid, earliest, clock.instant());
        for (com.memetrade.ledger.domain.EpochMark mark : plan) {
            insertMark(tenantId, botWalletId, mark);
            if (mark.reason() != com.memetrade.ledger.domain.EpochMarkReason.ZERO) {
                log.warn(
                        "event=REAL_POSITION_EPOCH_REMARKED reason={} botWalletId={} fullRead={}",
                        mark.reason(),
                        botWalletId,
                        !invalid.isEmpty());
            }
        }
        forget(botWalletId);
    }

    /** The latest mark of each mint of the wallet. */
    private java.util.Map<String, com.memetrade.ledger.domain.EpochMark> marks(UUID tenantId, UUID botWalletId) {
        java.util.Map<String, com.memetrade.ledger.domain.EpochMark> marks = new java.util.HashMap<>();
        jdbc.sql("""
                        select distinct on (mint) mint, zero_slot, zero_inherited, zero_tx_signature, zero_created_at,
                          zero_reference, zero_kind_rank, zero_idempotency_key, fills_through, window_from, reason
                        from real_position_epoch
                        where tenant_id = :tenant and bot_wallet_id = :wallet
                        order by mint, epoch_seq desc""").param("tenant", tenantId).param("wallet", botWalletId).query(rs -> {
            String mint = rs.getString("mint");
            long slot = rs.getLong("zero_slot");
            Optional<com.memetrade.ledger.api.NetworkOrderKey> zero = rs.wasNull()
                    ? Optional.empty()
                    : Optional.of(new com.memetrade.ledger.api.NetworkOrderKey(
                            slot,
                            rs.getBoolean("zero_inherited"),
                            Optional.ofNullable(rs.getString("zero_tx_signature")),
                            rs.getTimestamp("zero_created_at").toInstant(),
                            rs.getString("zero_reference"),
                            rs.getInt("zero_kind_rank"),
                            rs.getString("zero_idempotency_key")));
            marks.put(
                    mint,
                    new com.memetrade.ledger.domain.EpochMark(
                            mint,
                            zero,
                            rs.getLong("fills_through"),
                            rs.getTimestamp("window_from").toInstant(),
                            com.memetrade.ledger.domain.EpochMarkReason.valueOf(rs.getString("reason"))));
        });
        return marks;
    }

    private void insertMark(UUID tenantId, UUID botWalletId, com.memetrade.ledger.domain.EpochMark mark) {
        Optional<com.memetrade.ledger.api.NetworkOrderKey> zero = mark.zero();
        jdbc.sql("""
                        insert into real_position_epoch (tenant_id, bot_wallet_id, mint, zero_slot, zero_inherited,
                          zero_tx_signature, zero_created_at, zero_reference, zero_kind_rank, zero_idempotency_key,
                          fills_through, window_from, reason, recorded_at)
                        values (:tenant, :wallet, :mint, :slot, :inherited, :signature, :createdAt, :reference, :rank,
                          :key, :fills, :windowFrom, :reason, :at)""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .param("mint", mark.mint())
                .param(
                        "slot",
                        zero.map(com.memetrade.ledger.api.NetworkOrderKey::effectiveSlot)
                                .orElse(null),
                        java.sql.Types.BIGINT)
                .param(
                        "inherited",
                        zero.map(com.memetrade.ledger.api.NetworkOrderKey::inherited)
                                .orElse(null),
                        java.sql.Types.BOOLEAN)
                .param(
                        "signature",
                        zero.filter(k -> !k.inherited())
                                .flatMap(com.memetrade.ledger.api.NetworkOrderKey::txSignature)
                                .orElse(null),
                        java.sql.Types.VARCHAR)
                .param("createdAt", zero.map(k -> Timestamp.from(k.createdAt())).orElse(null), java.sql.Types.TIMESTAMP)
                .param(
                        "reference",
                        zero.map(com.memetrade.ledger.api.NetworkOrderKey::reference)
                                .orElse(null),
                        java.sql.Types.VARCHAR)
                .param(
                        "rank",
                        zero.map(com.memetrade.ledger.api.NetworkOrderKey::kindRank)
                                .orElse(null),
                        java.sql.Types.SMALLINT)
                .param(
                        "key",
                        zero.map(com.memetrade.ledger.api.NetworkOrderKey::idempotencyKey)
                                .orElse(null),
                        java.sql.Types.VARCHAR)
                .param("fills", mark.fillsThrough())
                .param("windowFrom", Timestamp.from(mark.windowFrom()))
                .param("reason", mark.reason().name())
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    // ----- the rows this transaction wrote (Etapa 14b)
    // ----------------------------------------------------------------

    /** What the current transaction wrote: the earliest row created per wallet and the signatures given a slot. */
    private record Writes(java.util.Map<UUID, Instant> created, java.util.Set<String> slotted) {}

    private final Object writesKey = new Object();

    /** The writes of the current transaction, bound to it; empty (and not kept) outside a transaction. */
    private Optional<Writes> writes() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            return Optional.empty();
        }
        Writes writes = (Writes)
                org.springframework.transaction.support.TransactionSynchronizationManager.getResource(writesKey);
        if (writes == null) {
            writes = new Writes(new java.util.HashMap<>(), new java.util.HashSet<>());
            org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(writesKey, writes);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            org.springframework.transaction.support.TransactionSynchronizationManager
                                    .unbindResourceIfPossible(writesKey);
                        }
                    });
        }
        return Optional.of(writes);
    }

    /**
     * The earliest creation instant of the rows of the wallet this transaction wrote or gave a slot to: a row created
     * before the window of a mark (a clock behind) lowers it; a slot given to an older row changes its key.
     */
    private Optional<Instant> earliestWrite(UUID tenantId, UUID botWalletId) {
        Optional<Writes> writes = writes();
        if (writes.isEmpty()) {
            return Optional.empty();
        }
        Optional<Instant> created = Optional.ofNullable(writes.get().created().get(botWalletId));
        if (writes.get().slotted().isEmpty()) {
            return created;
        }
        Optional<Instant> slotted = jdbc.sql("""
                        select min(created_at) from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and tx_signature in (:signatures)""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .param("signatures", List.copyOf(writes.get().slotted()))
                .query((rs, row) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
                .single();
        return java.util.stream.Stream.of(created, slotted)
                .flatMap(Optional::stream)
                .min(java.util.Comparator.naturalOrder());
    }

    private void forget(UUID botWalletId) {
        writes().ifPresent(writes -> writes.created().remove(botWalletId));
    }

    private void auditConflict(UUID tenantId, String txSignature, long recorded, long refused, SlotWriter writer) {
        AuditEventCommand event = new AuditEventCommand(
                        UUID.randomUUID(),
                        Optional.of(tenantId),
                        AuditEventCommand.SYSTEM_ACTOR,
                        ActorType.SYSTEM,
                        AuditActions.REAL_SLOT_CONFLICT,
                        "real-entry-slot",
                        CorrelationId.current(),
                        clock.instant(),
                        java.util.Map.of(
                                "recordedSlot", Long.toString(recorded),
                                "refusedSlot", Long.toString(refused),
                                "writer", writer.name()),
                        null)
                .withReal(RealReferences.hashedTransaction(txSignature));
        try {
            audit.publish(event);
        } catch (AuditPublishRejectedException refusedEvent) {
            // C13: the conflict never fails the confirmation. The publisher already counted and logged the refusal
            // (audit_publish_rejected_total); the slot stays the first one either way.
            log.error(
                    "event=REAL_SLOT_CONFLICT_AUDIT_REFUSED writer={} reason={} field={}",
                    writer,
                    refusedEvent.reason(),
                    refusedEvent.field());
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void streamEntries(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            Instant from,
            Instant to,
            java.util.function.Consumer<com.memetrade.ledger.api.RealEntryRecord> consumer) {
        if (botWalletIds.isEmpty()) {
            return;
        }
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("tenant", tenantId);
        params.put("wallets", List.copyOf(botWalletIds));
        params.put("from", Timestamp.from(from));
        params.put("to", Timestamp.from(to));
        named.query("""
                select entry_kind, lamports, mint, token_base_units, tx_signature, reference, created_at
                from (
                  select e.*, min(e.created_at) over (partition by e.reference) as reference_at
                  from real_ledger_entry e
                  where e.tenant_id = :tenant and e.created_at >= :from and e.created_at < :to and e.mode = 'REAL'
                ) entries
                where bot_wallet_id in (:wallets)
                order by reference_at, reference, created_at, entry_id""", params, rs -> {
            BigDecimal units = rs.getBigDecimal("token_base_units");
            consumer.accept(new com.memetrade.ledger.api.RealEntryRecord(
                    RealEntryKind.valueOf(rs.getString("entry_kind")),
                    rs.getLong("lamports"),
                    Optional.ofNullable(rs.getString("mint")),
                    Optional.ofNullable(units).map(BigDecimal::longValueExact),
                    Optional.ofNullable(rs.getString("tx_signature")),
                    rs.getString("reference"),
                    rs.getTimestamp("created_at").toInstant()));
        });
    }

    /**
     * 261121-paper-real-unico U2 (C12, BR1.1 to BR1.3, BR1.5, BR1.6, decision F-1; performance-design.md "Forma da
     * consulta principal"). Public only for the plan tests (RealLedgerNetworkOrderPlanIT, PnlPlanSqlParityTest), which
     * run EXPLAIN on this exact text.
     *
     * <ol>
     *   <li>every row of the tenant and the wallets asked for, of every kind, joined left to {@code real_entry_slot} by
     *       the signature;
     *   <li>the inheritance: per wallet, in recording order {@code (created_at, reference, kind_rank, idempotency_key)},
     *       a running count of the recorded rows makes the groups; a row without a recorded slot takes the slot of the
     *       recorded row that opened its group (0 before the first one);
     *   <li>the kinds asked for are filtered only then, in the outer query;
     *   <li>the order: wallet, effective slot, recorded rows before inherited ones, the signature only among recorded
     *       rows, then createdAt, reference, kind rank and idempotency key. Never the entry id.
     * </ol>
     *
     * Every tie-break of text uses {@code COLLATE "C"}, so the order is the one of the bytes whatever the collation of
     * the database.
     */
    public static final String NETWORK_ORDER_SQL = """
            with entries as (
              select e.entry_id, e.bot_wallet_id, e.entry_kind, e.lamports, e.mint, e.token_base_units,
                e.tx_signature, e.reference, e.idempotency_key, e.created_at, s.slot as recorded_slot,
                case e.entry_kind when 'SWAP_FILL_IN' then 0 when 'SWAP_FILL_OUT' then 0 when 'NETWORK_FEE' then 1
                  when 'RENT' then 2 else 3 end as kind_rank
              from real_ledger_entry e
              left join real_entry_slot s on s.tx_signature = e.tx_signature
              where e.tenant_id = :tenant and e.bot_wallet_id in (:wallets) and e.mode = 'REAL'
            ), grouped as (
              select entries.*, count(recorded_slot) over (partition by bot_wallet_id
                order by created_at, reference collate "C", kind_rank, idempotency_key collate "C"
                rows between unbounded preceding and current row) as slot_group
              from entries
            ), keyed as (
              select grouped.*, coalesce(recorded_slot,
                max(recorded_slot) over (partition by bot_wallet_id, slot_group), 0) as effective_slot
              from grouped
            )
            select entry_id, bot_wallet_id, entry_kind, lamports, mint, token_base_units, tx_signature, reference,
              idempotency_key, created_at, recorded_slot, kind_rank, effective_slot
            from keyed
            where entry_kind in (:kinds)
            order by bot_wallet_id, effective_slot, recorded_slot is null,
              (case when recorded_slot is not null then tx_signature end) collate "C",
              created_at, reference collate "C", kind_rank, idempotency_key collate "C\"""";

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void streamInNetworkOrder(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            java.util.Set<RealEntryKind> kinds,
            java.util.function.Consumer<com.memetrade.ledger.api.NetworkOrderedEntry> consumer) {
        stream(tenantId, botWalletIds, kinds, null, consumer);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void streamInNetworkOrder(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            java.util.Set<RealEntryKind> kinds,
            com.memetrade.shared.jdbc.ReadDeadline deadline,
            java.util.function.Consumer<com.memetrade.ledger.api.NetworkOrderedEntry> consumer) {
        stream(tenantId, botWalletIds, kinds, java.util.Objects.requireNonNull(deadline, "deadline"), consumer);
    }

    private void stream(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            java.util.Set<RealEntryKind> kinds,
            com.memetrade.shared.jdbc.ReadDeadline deadline,
            java.util.function.Consumer<com.memetrade.ledger.api.NetworkOrderedEntry> consumer) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        if (botWalletIds.isEmpty() || kinds.isEmpty()) {
            return;
        }
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("tenant", tenantId);
        params.put("wallets", List.copyOf(botWalletIds));
        params.put("kinds", kinds.stream().map(Enum::name).toList());
        long[] missing = {0};
        org.springframework.jdbc.core.RowCallbackHandler rows = rs -> {
            com.memetrade.ledger.api.NetworkOrderedEntry entry = networkOrdered(rs);
            if (entry.slotStatus() == com.memetrade.ledger.api.SlotStatus.SLOT_MISSING) {
                missing[0]++;
            }
            consumer.accept(entry);
        };
        java.util.function.Consumer<String> execute =
                sql -> named.getJdbcTemplate().execute(sql);
        try {
            if (deadline == null) {
                named.query(NETWORK_ORDER_SQL, params, rows);
            } else {
                deadline.applyStatementTimeout(execute);
                named.query(NETWORK_ORDER_SQL, params, deadline.guard(rows, STREAM_FETCH_SIZE, execute));
            }
        } finally {
            slotMetrics.slotMissing(missing[0]);
        }
    }

    private static com.memetrade.ledger.api.NetworkOrderedEntry networkOrdered(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        RealEntryKind kind = RealEntryKind.valueOf(rs.getString("entry_kind"));
        Optional<String> signature = Optional.ofNullable(rs.getString("tx_signature"));
        long recordedSlot = rs.getLong("recorded_slot");
        boolean recorded = !rs.wasNull();
        String reference = rs.getString("reference");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        com.memetrade.ledger.api.SlotStatus status = recorded
                ? com.memetrade.ledger.api.SlotStatus.RECORDED
                : signature.isPresent()
                        ? com.memetrade.ledger.api.SlotStatus.SLOT_MISSING
                        : com.memetrade.ledger.api.SlotStatus.NO_NETWORK_TX;
        com.memetrade.ledger.api.NetworkOrderKey key = new com.memetrade.ledger.api.NetworkOrderKey(
                rs.getLong("effective_slot"),
                !recorded,
                signature,
                createdAt,
                reference,
                rs.getInt("kind_rank"),
                rs.getString("idempotency_key"));
        BigDecimal units = rs.getBigDecimal("token_base_units");
        return new com.memetrade.ledger.api.NetworkOrderedEntry(
                rs.getObject("entry_id", UUID.class),
                rs.getObject("bot_wallet_id", UUID.class),
                kind,
                rs.getLong("lamports"),
                Optional.ofNullable(rs.getString("mint")),
                Optional.ofNullable(units).map(BigDecimal::longValueExact),
                signature,
                reference,
                createdAt,
                key,
                status);
    }

    @Override
    public boolean record(RealEntry entry) {
        RealEntryRules.validate(entry);
        Instant at = clock.instant();
        boolean recorded = jdbc.sql("""
                                insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                                  tx_signature, reference, idempotency_key, created_at, mint, token_base_units)
                                values (:id, :tenant, :wallet, :kind, :lamports, :signature, :reference, :key, :at,
                                  :mint, :units)
                                on conflict (idempotency_key) do nothing""")
                        .param("id", UUID.randomUUID())
                        .param("tenant", entry.tenantId())
                        .param("wallet", entry.botWalletId())
                        .param("kind", entry.kind().name())
                        .param("lamports", entry.lamports())
                        .param("signature", entry.txSignature())
                        .param("reference", entry.reference())
                        .param("key", entry.idempotencyKey())
                        .param("at", Timestamp.from(at))
                        .param("mint", entry.mint())
                        .param(
                                "units",
                                entry.tokenBaseUnits() == null ? null : BigDecimal.valueOf(entry.tokenBaseUnits()))
                        .update()
                == 1;
        if (recorded) {
            // Etapa 14b: markEpochs of the same transaction lowers the window of a mark created after this row.
            writes().ifPresent(
                            writes -> writes.created().merge(entry.botWalletId(), at, (a, b) -> a.isBefore(b) ? a : b));
        }
        return recorded;
    }

    @Override
    public RealBalance balance(UUID tenantId, UUID botWalletId) {
        return RealBalances.of(jdbc.sql("""
                        select entry_kind, reference, sum(lamports) as lamports
                        from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL'
                        group by entry_kind, reference""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .query((rs, row) -> new RealLedgerRow(
                        RealEntryKind.valueOf(rs.getString("entry_kind")),
                        rs.getBigDecimal("lamports").longValueExact(),
                        rs.getString("reference")))
                .list());
    }

    /**
     * 261121-paper-real-unico U2 (BR2.5, F-3): the quantity-only path in network order, read by the same single
     * statement as {@link #view}; the cost is not read ({@code Unknown(NOT_READ)}). No order, no bot, no attribution.
     */
    @Override
    public List<RealPosition> positions(UUID tenantId, UUID botWalletId) {
        return view(tenantId, botWalletId, Optional.empty()).positions();
    }

    @Override
    public RealFlows flowsSince(UUID tenantId, UUID botWalletId, Instant since) {
        return jdbc.sql("""
                        select
                          coalesce(sum(lamports) filter (where entry_kind = 'DEPOSIT'), 0) as deposited,
                          coalesce(-sum(lamports) filter (where entry_kind = 'WITHDRAWAL'
                            or (entry_kind = 'NETWORK_FEE' and reference like 'wd:%')), 0) as withdrawn
                        from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL'
                          and created_at > :since""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .param("since", Timestamp.from(since))
                .query((rs, row) -> new RealFlows(
                        rs.getBigDecimal("deposited").longValueExact(),
                        rs.getBigDecimal("withdrawn").longValueExact()))
                .single();
    }

    @Override
    public java.util.Map<String, Long> tokenBalances(UUID tenantId, UUID botWalletId) {
        java.util.Map<String, Long> balances = new java.util.TreeMap<>();
        jdbc.sql("""
                        select mint, sum(case entry_kind when 'SWAP_FILL_OUT' then -token_base_units
                          else token_base_units end) as units
                        from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL' and mint is not null
                          and entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT', 'COMPENSATION')
                        group by mint""").param("tenant", tenantId).param("wallet", botWalletId).query(rs -> {
            long units = rs.getBigDecimal("units").longValueExact();
            if (units != 0) {
                balances.put(rs.getString("mint"), units);
            }
        });
        return balances;
    }

    /**
     * The rows of the wallet (W, the same filter as the selection of {@link #NETWORK_ORDER_SQL}), with the slot of
     * {@code real_entry_slot} and the inheritance of BR1.3 computed over every row, as in the read in network order.
     */
    private static final String VIEW_ROWS = """
            with entries as (
              select e.entry_kind, e.lamports, e.mint, e.token_base_units, e.tx_signature, e.reference,
                e.idempotency_key, e.created_at, s.slot as recorded_slot,
                case e.entry_kind when 'SWAP_FILL_IN' then 0 when 'SWAP_FILL_OUT' then 0 when 'NETWORK_FEE' then 1
                  when 'RENT' then 2 else 3 end as kind_rank
              from real_ledger_entry e
              left join real_entry_slot s on s.tx_signature = e.tx_signature
              where e.tenant_id = :tenant and e.bot_wallet_id = :wallet and e.mode = 'REAL'
            ), grouped as (
              select entries.*, count(recorded_slot) over (
                order by created_at, reference collate "C", kind_rank, idempotency_key collate "C"
                rows between unbounded preceding and current row) as slot_group
              from entries
            ), keyed as (
              select grouped.*, coalesce(recorded_slot, max(recorded_slot) over (partition by slot_group), 0)
                as effective_slot
              from grouped
            ), parts as (
              select 'B' as part, entry_kind, reference, sum(lamports)::numeric as amount, null::numeric as other,
                null::varchar as mint, null::bigint as effective_slot, null::boolean as inherited,
                null::varchar collate "C" as signature_key, null::timestamptz as created_at,
                null::varchar collate "C" as reference_key, null::int as kind_rank,
                null::varchar collate "C" as idempotency_key
              from entries group by entry_kind, reference
              union all
              select 'C', null, null, count(*)::numeric, null, null, null, null, null, null, null, null, null
              from entries
              union all
              select 'P', entry_kind, reference, lamports::numeric, token_base_units, mint, effective_slot,
                recorded_slot is null,
                (case when recorded_slot is not null then tx_signature end) collate "C", created_at,
                reference collate "C", kind_rank, idempotency_key collate "C"
              from keyed where entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT', 'RENT')
            """;

    private static final String VIEW_FLOWS = """
              union all
              select 'F', null, null,
                coalesce(sum(lamports) filter (where entry_kind = 'DEPOSIT'), 0)::numeric,
                coalesce(-sum(lamports) filter (where entry_kind = 'WITHDRAWAL'
                  or (entry_kind = 'NETWORK_FEE' and reference like 'wd:%')), 0)::numeric,
                null, null, null, null, null, null, null, null
              from entries where created_at > :since
            """;

    private static final String VIEW_ORDER = """
            )
            select * from parts
            order by part, effective_slot, inherited, signature_key, created_at, reference_key, kind_rank,
              idempotency_key""";

    /**
     * 261113-trading-real-bot U3 (review R-01) and 261121-paper-real-unico U2 (BR2.5, F-3): the balance rows (B), the
     * count of the rows of the wallet (C) and the swap fills in NETWORK order (P), as one statement. Public only for the
     * plan test (RealLedgerNetworkOrderPlanIT).
     */
    public static final String VIEW_SQL = VIEW_ROWS + VIEW_ORDER;

    /** {@link #VIEW_SQL} with the flows after {@code :since} (F). */
    public static final String VIEW_WITH_FLOWS_SQL = VIEW_ROWS + VIEW_FLOWS + VIEW_ORDER;

    /**
     * 261113-trading-real-bot U3 (review R-01): the reads of {@link #balance}, {@link #positions} and
     * {@link #flowsSince} in ONE statement over the same rows, so they share one snapshot of PostgreSQL even in READ
     * COMMITTED: a fill or a deposit committed meanwhile is in every part or in none. 261121-paper-real-unico U2: the
     * positions come from the quantity-only path in network order (cost NOT read) and the same statement counts the
     * rows of the wallet.
     *
     * <p>261121-paper-real-unico U2a, Etapa 14b: read by {@link #VIEW_EPOCH_SQL}, restricted by the marks of zero, with
     * the default {@code work_mem}. When the fills of a mint disagree with its latest mark, the same view is read again
     * from the whole history ({@link #VIEW_SQL}, with {@link #VIEW_WORK_MEM}), counted in
     * {@code ledger_real_epoch_fallbacks_total{reason="COUNT_MISMATCH"}} and logged; the answer is the same. A mint
     * without a mark (never zeroed since the marks exist) is read from its first fill: the worst case, the whole history
     * of a position held since then.
     */
    @Override
    public RealLedgerView view(UUID tenantId, UUID botWalletId, Optional<Instant> flowsSince) {
        EpochRead read = readEpochs(tenantId, botWalletId, flowsSince, Optional.empty());
        if (!read.invalid().isEmpty()) {
            slotMetrics.epochFallback();
            log.warn(
                    "event=REAL_POSITION_EPOCH_FALLBACK reason={} botWalletId={} mints={}",
                    LedgerRealSlotMetrics.EPOCH_COUNT_MISMATCH,
                    botWalletId,
                    read.invalid().size());
            read = readWhole(tenantId, botWalletId, flowsSince);
        }
        return new RealLedgerView(
                RealBalances.of(read.balanceRows()),
                read.epochs().quantities().positions(),
                read.flows(),
                read.rows(),
                read.epochs().openLots());
    }

    /** One read of the view: its parts, and the mints whose mark disagreed with their fills (restricted read only). */
    private record EpochRead(
            List<RealLedgerRow> balanceRows,
            com.memetrade.ledger.domain.RealEpochs epochs,
            RealFlows flows,
            long rows,
            java.util.Set<String> invalid) {}

    /** {@link #VIEW_EPOCH_SQL}: restricted by the marks, their windows lowered to {@code since} when present. */
    private EpochRead readEpochs(
            UUID tenantId, UUID botWalletId, Optional<Instant> flowsSince, Optional<Instant> since) {
        var statement = jdbc.sql(VIEW_EPOCH_SQL)
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .param("since", since.map(Timestamp::from).orElse(null), java.sql.Types.TIMESTAMP)
                .param("flows", flowsSince.map(Timestamp::from).orElse(null), java.sql.Types.TIMESTAMP);
        return readView(botWalletId, statement, flowsSince.isPresent());
    }

    /** The whole history ({@link #VIEW_SQL}): the fallback, with its own local work_mem in a transaction. */
    private EpochRead readWhole(UUID tenantId, UUID botWalletId, Optional<Instant> flowsSince) {
        var statement = jdbc.sql(flowsSince.isPresent() ? VIEW_WITH_FLOWS_SQL : VIEW_SQL)
                .param("tenant", tenantId)
                .param("wallet", botWalletId);
        if (flowsSince.isPresent()) {
            statement = statement.param("since", Timestamp.from(flowsSince.get()));
        }
        // 261121-paper-real-unico U2a, Etapa 14a: the inheritance sorts every row of the wallet, with VIEW_WORK_MEM.
        var full = statement;
        return withLocalSettings(
                java.util.Map.of("work_mem", VIEW_WORK_MEM), () -> readView(botWalletId, full, flowsSince.isPresent()));
    }

    /**
     * In a transaction, {@code settings} apply to the statement only: set locally before it, and the values read before
     * applied again right after it. Outside a transaction nothing is set.
     */
    private <T> T withLocalSettings(java.util.Map<String, String> settings, java.util.function.Supplier<T> read) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            return read.get();
        }
        java.util.Map<String, String> before = new java.util.TreeMap<>();
        new java.util.TreeMap<>(settings).forEach((name, value) -> {
            before.put(name, jdbc.sql("show " + name).query(String.class).single());
            jdbc.sql("set local " + name + " = '" + value + "'").update();
        });
        try {
            return read.get();
        } finally {
            before.forEach((name, value) ->
                    jdbc.sql("set local " + name + " = '" + value + "'").update());
        }
    }

    /**
     * Etapa 14a: the local work_mem of the full view, now only the fallback of Etapa 14b (the plan test reads the same
     * value). The view restricted by the marks runs with the default.
     */
    public static final String VIEW_WORK_MEM = "12MB";

    private static EpochRead readView(UUID botWalletId, JdbcClient.StatementSpec statement, boolean withFlows) {
        List<RealLedgerRow> balanceRows = new ArrayList<>();
        com.memetrade.ledger.domain.RealEpochs epochs = new com.memetrade.ledger.domain.RealEpochs();
        RealFlows[] flows = {RealFlows.NONE};
        long[] rows = {0};
        java.util.Set<String> invalid = new java.util.TreeSet<>();
        statement.query(rs -> {
            switch (rs.getString("part")) {
                case "B" ->
                    balanceRows.add(new RealLedgerRow(
                            RealEntryKind.valueOf(rs.getString("entry_kind")),
                            rs.getBigDecimal("amount").longValueExact(),
                            rs.getString("reference")));
                case "C" -> rows[0] = rs.getBigDecimal("amount").longValueExact();
                case "P" -> epochs.accept(lotRow(rs, botWalletId));
                case "V" -> invalid.add(rs.getString("mint"));
                default -> {
                    if (withFlows) {
                        flows[0] = new RealFlows(
                                rs.getBigDecimal("amount").longValueExact(),
                                rs.getBigDecimal("other").longValueExact());
                    }
                }
            }
        });
        return new EpochRead(balanceRows, epochs, flows[0], rows[0], invalid);
    }

    /** One swap fill or RENT of the part P of the view, with its network order key. */
    private static com.memetrade.ledger.api.NetworkOrderedEntry lotRow(java.sql.ResultSet rs, UUID botWalletId)
            throws java.sql.SQLException {
        RealEntryKind kind = RealEntryKind.valueOf(rs.getString("entry_kind"));
        boolean inherited = rs.getBoolean("inherited");
        Optional<String> signature = Optional.ofNullable(rs.getString("signature_key"));
        String key = rs.getString("idempotency_key");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        BigDecimal units = rs.getBigDecimal("other");
        return new com.memetrade.ledger.api.NetworkOrderedEntry(
                UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                botWalletId,
                kind,
                rs.getBigDecimal("amount").longValueExact(),
                Optional.ofNullable(rs.getString("mint")),
                Optional.ofNullable(units).map(BigDecimal::longValueExact),
                signature,
                rs.getString("reference"),
                createdAt,
                new com.memetrade.ledger.api.NetworkOrderKey(
                        rs.getLong("effective_slot"),
                        inherited,
                        signature,
                        createdAt,
                        rs.getString("reference_key"),
                        rs.getInt("kind_rank"),
                        key),
                inherited
                        ? com.memetrade.ledger.api.SlotStatus.SLOT_MISSING
                        : com.memetrade.ledger.api.SlotStatus.RECORDED);
    }
}
