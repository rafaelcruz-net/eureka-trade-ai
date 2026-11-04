package com.memetrade.pnl.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.pnl.application.ReconciliationService.Divergence;
import com.memetrade.pnl.application.ReconciliationService.Result;
import com.memetrade.pnl.application.ReconciliationService.Trigger;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code reconciliation_check}, owned by the {@code pnl} module; the divergences are stored as JSON. */
@Repository
public class JdbcReconciliationStore {

    private static final TypeReference<List<Divergence>> DIVERGENCES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcReconciliationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** reliability-design.md: the reconciliation transaction has a statement timeout of 30 s. */
    public void setStatementTimeout() {
        jdbc.sql("select set_config('statement_timeout', '30s', true)")
                .query(String.class)
                .single();
    }

    public void insert(UUID tenantId, UUID ownerUserId, Result result) {
        jdbc.sql("""
                        insert into reconciliation_check (check_id, tenant_id, owner_user_id, mode, status,
                          divergence_count, divergences, check_trigger, checked_at)
                        values (:check, :tenant, :user, 'PAPER', :status, :count, cast(:divergences as jsonb),
                          :trigger, :at)
                        """)
                .param("check", result.checkId())
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("status", result.reconciled() ? "RECONCILED" : "DIVERGENT")
                .param("count", result.divergences().size())
                .param("divergences", write(result.divergences()))
                .param("trigger", result.trigger().name())
                .param("at", Timestamp.from(result.checkedAt()))
                .update();
    }

    /** BR8.1: keeps the last {@code keep} checks of the person. */
    public void keepLatest(UUID tenantId, UUID ownerUserId, int keep) {
        jdbc.sql("""
                        delete from reconciliation_check
                        where tenant_id = :tenant and owner_user_id = :user and check_id in (
                          select check_id from reconciliation_check
                          where tenant_id = :tenant and owner_user_id = :user
                          order by checked_at desc, check_id desc offset :keep)
                        """)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("keep", keep)
                .update();
    }

    public Optional<Result> latest(UUID tenantId, UUID ownerUserId) {
        return jdbc.sql("""
                        select check_id, check_trigger, checked_at, divergences::text as divergences
                        from reconciliation_check
                        where tenant_id = :tenant and owner_user_id = :user and mode = 'PAPER'
                        order by checked_at desc, check_id desc limit 1
                        """)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .query((rs, row) -> new Result(
                        rs.getObject("check_id", UUID.class),
                        Trigger.valueOf(rs.getString("check_trigger")),
                        rs.getTimestamp("checked_at").toInstant(),
                        read(rs.getString("divergences"))))
                .optional();
    }

    private String write(List<Divergence> divergences) {
        try {
            return json.writeValueAsString(divergences);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The divergences could not be serialised", failure);
        }
    }

    private List<Divergence> read(String text) {
        try {
            return json.readValue(text, DIVERGENCES);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The stored divergences could not be read", failure);
        }
    }
}
