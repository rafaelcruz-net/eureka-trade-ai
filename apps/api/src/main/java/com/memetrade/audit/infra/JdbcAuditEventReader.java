package com.memetrade.audit.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.domain.AuditCursor;
import com.memetrade.audit.domain.AuditEventQuery;
import com.memetrade.audit.domain.AuditEventReader;
import com.memetrade.audit.domain.AuditEventRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keyset query of the trail (performance-design.md): {@code (occurred_at, event_id) < cursor}, ordered descending,
 * {@code limit + 1} rows, no OFFSET. The action filter is a separate prepared statement so each form uses its own
 * index ({@code audit_event_time_id_idx} or {@code audit_event_action_time_id_idx}) without a separate sort.
 * Parameters are always bound; nothing from the request is concatenated into SQL.
 */
@Repository
public class JdbcAuditEventReader implements AuditEventReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> DETAILS = new TypeReference<>() {};

    private final JdbcClient jdbc;

    public JdbcAuditEventReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AuditEventRecord> read(AuditEventQuery query, int rows) {
        JdbcClient.StatementSpec statement = jdbc.sql(
                        sql(query.action().isPresent(), query.cursor().isPresent()))
                .param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .param("rows", rows);
        if (query.action().isPresent()) {
            statement = statement.param("action", query.action().get());
        }
        if (query.cursor().isPresent()) {
            AuditCursor cursor = query.cursor().get();
            statement = statement
                    .param("cursorAt", Timestamp.from(cursor.occurredAt()))
                    .param("cursorId", cursor.eventId());
        }
        return statement.query(JdbcAuditEventReader::map).list();
    }

    /** One of the four prepared forms; package-visible for the EXPLAIN check of the integration test. */
    static String sql(boolean withAction, boolean withCursor) {
        return """
                select event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id, occurred_at,
                       recorded_at, details::text as details
                from audit_event
                where occurred_at >= :from and occurred_at < :to
                """
                + (withAction ? "  and action = :action\n" : "")
                + (withCursor ? "  and (occurred_at, event_id) < (:cursorAt, :cursorId)\n" : "")
                + "order by occurred_at desc, event_id desc\nlimit :rows";
    }

    private static AuditEventRecord map(ResultSet rs, int row) throws SQLException {
        Timestamp recorded = rs.getTimestamp("recorded_at");
        return new AuditEventRecord(
                rs.getObject("event_id", UUID.class),
                Optional.ofNullable(rs.getObject("tenant_id", UUID.class)),
                rs.getString("actor"),
                ActorType.valueOf(rs.getString("actor_type")),
                rs.getString("action"),
                rs.getString("target_ref"),
                rs.getString("correlation_id"),
                rs.getTimestamp("occurred_at").toInstant(),
                Optional.ofNullable(recorded).map(Timestamp::toInstant),
                details(rs.getString("details")));
    }

    private static Map<String, String> details(String json) {
        try {
            return JSON.readValue(json, DETAILS);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("audit_event.details is not a JSON object of text values", corrupt);
        }
    }
}
