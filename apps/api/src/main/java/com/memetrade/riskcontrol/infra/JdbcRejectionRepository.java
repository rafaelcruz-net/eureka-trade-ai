package com.memetrade.riskcontrol.infra;

import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import com.memetrade.riskcontrol.domain.DetailUnit;
import com.memetrade.riskcontrol.domain.ReasonDetail;
import com.memetrade.riskcontrol.domain.RejectionRecord;
import com.memetrade.riskcontrol.domain.RejectionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code order_rejection} and {@code rejection_reason_detail} through the main pool. Writes run in the caller's (cycle)
 * transaction; the details of a rejection go in one batch. Every read carries tenant, person and {@code mode = 'PAPER'}
 * (NFR5.1, NFR5.3): the page is served by the index (tenant, person, mode, bot, evaluated_at DESC, rejection_id DESC),
 * the details of the page by one more query, and the total by a count bounded at 10001 rows (performance-design.md).
 */
@Repository
public class JdbcRejectionRepository implements RejectionRepository {

    private static final String FILTER =
            "tenant_id = :tenant and owner_user_id = :owner and mode = 'PAPER' and bot_id = :bot";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public JdbcRejectionRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    @Override
    public void insert(RejectionRecord rejection) {
        insertAll(List.of(rejection));
    }

    @Override
    public void insertAll(List<RejectionRecord> all) {
        batch.batchUpdate(
                """
                insert into order_rejection (rejection_id, tenant_id, owner_user_id, mode, bot_id, decision_id,
                    evaluation_id, side, chain, token_mint, requested_quantity, reasons, pause_triggers,
                    limits_version, market, evaluated_at)
                values (:id, :tenant, :owner, 'PAPER', :bot, :decision, :evaluation, :side, :chain, :mint,
                    :quantity, :reasons, :triggers, :version, cast(:market as jsonb), :at)
                """, all.stream().map(JdbcRejectionRepository::rejection).toArray(SqlParameterSource[]::new));
        batch.batchUpdate(
                """
                insert into rejection_reason_detail (rejection_id, tenant_id, owner_user_id, mode, reason, measured,
                    limit_value, unit)
                values (:rejection, :tenant, :owner, 'PAPER', :reason, :measured, :limit, :unit)
                """,
                all.stream()
                        .flatMap(rejection -> rejection.details().stream().map(detail -> detail(rejection, detail)))
                        .toArray(SqlParameterSource[]::new));
    }

    private static SqlParameterSource rejection(RejectionRecord rejection) {
        return new MapSqlParameterSource()
                .addValue("id", rejection.rejectionId())
                .addValue("tenant", rejection.tenantId())
                .addValue("owner", rejection.ownerUserId())
                .addValue("bot", rejection.botId())
                .addValue("decision", rejection.decisionId())
                .addValue("evaluation", rejection.evaluationId())
                .addValue("side", rejection.side().name())
                .addValue("chain", rejection.chain())
                .addValue("mint", rejection.tokenMint())
                .addValue("quantity", rejection.requestedQuantity())
                .addValue(
                        "reasons", rejection.reasons().stream().map(Enum::name).collect(Collectors.joining(",")))
                .addValue(
                        "triggers",
                        rejection.pauseTriggers().stream().map(Enum::name).collect(Collectors.joining(",")))
                .addValue("version", rejection.limitsVersion())
                .addValue("market", MarketInputsCodec.write(rejection.market()))
                .addValue("at", Timestamp.from(rejection.evaluatedAt()));
    }

    private static SqlParameterSource detail(RejectionRecord rejection, ReasonDetail detail) {
        return new MapSqlParameterSource()
                .addValue("rejection", rejection.rejectionId())
                .addValue("tenant", rejection.tenantId())
                .addValue("owner", rejection.ownerUserId())
                .addValue("reason", detail.reason().name())
                .addValue("measured", detail.measured())
                .addValue("limit", detail.limit())
                .addValue("unit", detail.unit().name());
    }

    @Override
    public RejectionPage page(UUID tenantId, UUID ownerUserId, UUID botId, Optional<PageKey> after, int limit) {
        String keyset = after.isPresent() ? " and (evaluated_at, rejection_id) < (:afterAt, :afterId)" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("select * from order_rejection where " + FILTER + keyset
                        + " order by evaluated_at desc, rejection_id desc limit :limit")
                .param("tenant", tenantId)
                .param("owner", ownerUserId)
                .param("bot", botId)
                .param("limit", limit + 1);
        if (after.isPresent()) {
            statement = statement
                    .param("afterAt", Timestamp.from(after.get().evaluatedAt()))
                    .param("afterId", after.get().rejectionId());
        }
        List<Row> rows = statement.query(JdbcRejectionRepository::row).list();
        boolean more = rows.size() > limit;
        List<Row> pageRows = more ? rows.subList(0, limit) : rows;
        Map<UUID, List<ReasonDetail>> details = details(tenantId, ownerUserId, pageRows);
        List<RejectionRecord> items = pageRows.stream()
                .map(row -> row.toRecord(details.getOrDefault(row.rejectionId(), List.of())))
                .toList();
        Optional<PageKey> next = more
                ? Optional.of(new PageKey(
                        items.getLast().evaluatedAt(), items.getLast().rejectionId()))
                : Optional.empty();
        long counted = count(tenantId, ownerUserId, botId);
        return new RejectionPage(
                items, next, Math.min(counted, RejectionPage.TOTAL_CAP), counted > RejectionPage.TOTAL_CAP);
    }

    /** The details of the page in one query (never one per item), sorted by the fixed order of the reasons. */
    private Map<UUID, List<ReasonDetail>> details(UUID tenantId, UUID ownerUserId, List<Row> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select rejection_id, reason, measured, limit_value, unit from rejection_reason_detail
                        where rejection_id in (:ids) and tenant_id = :tenant and owner_user_id = :owner
                          and mode = 'PAPER'
                        """)
                .param("ids", rows.stream().map(Row::rejectionId).toList())
                .param("tenant", tenantId)
                .param("owner", ownerUserId)
                .query((rs, n) -> Map.entry(
                        rs.getObject("rejection_id", UUID.class),
                        new ReasonDetail(
                                RejectionReason.valueOf(rs.getString("reason")),
                                rs.getString("measured"),
                                rs.getString("limit_value"),
                                DetailUnit.valueOf(rs.getString("unit")))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(
                                Collectors.mapping(Map.Entry::getValue, Collectors.toList()), list -> list.stream()
                                        .sorted(Comparator.comparing(ReasonDetail::reason))
                                        .toList())));
    }

    /** Bounded count through the page index: at most 10001 rows are counted. */
    private long count(UUID tenantId, UUID ownerUserId, UUID botId) {
        return jdbc.sql("select count(*) from (select 1 from order_rejection where " + FILTER + " limit :cap) bounded")
                .param("tenant", tenantId)
                .param("owner", ownerUserId)
                .param("bot", botId)
                .param("cap", RejectionPage.TOTAL_CAP + 1)
                .query(Long.class)
                .single();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("rejection_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getObject("decision_id", UUID.class),
                rs.getObject("evaluation_id", UUID.class),
                OrderSide.valueOf(rs.getString("side")),
                rs.getString("chain"),
                rs.getString("token_mint"),
                rs.getBigDecimal("requested_quantity"),
                rs.getString("pause_triggers"),
                rs.getLong("limits_version"),
                rs.getString("market"),
                rs.getTimestamp("evaluated_at").toInstant());
    }

    private record Row(
            UUID rejectionId,
            UUID tenantId,
            UUID ownerUserId,
            UUID botId,
            UUID decisionId,
            UUID evaluationId,
            OrderSide side,
            String chain,
            String tokenMint,
            java.math.BigDecimal requestedQuantity,
            String pauseTriggers,
            long limitsVersion,
            String market,
            java.time.Instant evaluatedAt) {

        RejectionRecord toRecord(List<ReasonDetail> details) {
            Set<PauseTrigger> triggers = EnumSet.noneOf(PauseTrigger.class);
            if (!pauseTriggers.isEmpty()) {
                Arrays.stream(pauseTriggers.split(","))
                        .map(PauseTrigger::valueOf)
                        .forEach(triggers::add);
            }
            return new RejectionRecord(
                    rejectionId,
                    tenantId,
                    ownerUserId,
                    botId,
                    decisionId,
                    evaluationId,
                    side,
                    chain,
                    tokenMint,
                    requestedQuantity,
                    details,
                    triggers,
                    limitsVersion,
                    MarketInputsCodec.read(market),
                    evaluatedAt);
        }
    }
}
