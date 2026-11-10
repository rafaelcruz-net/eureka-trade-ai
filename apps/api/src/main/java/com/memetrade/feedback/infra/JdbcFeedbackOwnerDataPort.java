package com.memetrade.feedback.infra;

import com.memetrade.feedback.api.FeedbackOwnerDataPort;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** J3 of u4-data-requests: one count by tenant and person through {@code feedback_entry_person_time_idx}; read only. */
@Repository
public class JdbcFeedbackOwnerDataPort implements FeedbackOwnerDataPort {

    private final JdbcClient jdbc;

    public JdbcFeedbackOwnerDataPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countByUser(UUID tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Long count = jdbc.sql("select count(*) from feedback_entry where user_id = :user and tenant_id = :tenant")
                .param("user", userId)
                .param("tenant", tenantId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int deleteByUser(UUID tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        // BR5.8: the SECURITY DEFINER function of migration 0015 is the only way; the trigger refuses a direct DELETE.
        Integer erased = jdbc.sql("select erase_feedback_of_user(:tenant, :user)")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Integer.class)
                .single();
        return erased == null ? 0 : erased;
    }
}
