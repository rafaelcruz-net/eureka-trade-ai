package com.memetrade.ledger.infra;

import com.memetrade.ledger.api.PaperOrderCountPort;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * J4 of u4-data-requests: one count by owner, tenant and PAPER through {@code paper_order_owner_status_idx}; read only.
 */
@Repository
public class JdbcPaperOrderCountPort implements PaperOrderCountPort {

    private final JdbcClient jdbc;

    public JdbcPaperOrderCountPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countPaperOrders(UUID tenantId, UUID ownerUserId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Long count = jdbc.sql("""
                        select count(*) from paper_order
                        where owner_user_id = :owner and tenant_id = :tenant and mode = 'PAPER'
                        """)
                .param("owner", ownerUserId)
                .param("tenant", tenantId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }
}
