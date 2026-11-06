package com.memetrade.identity.infra;

import com.memetrade.identity.api.TenantOption;
import com.memetrade.identity.domain.invite.IdentityDirectory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tenants and guest count from the identity tables; the count uses the index app_user(role, status) (NFR2.4). */
@Repository
public class JdbcIdentityDirectory implements IdentityDirectory {

    private final JdbcClient jdbc;

    public JdbcIdentityDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantState> tenant(UUID tenantId) {
        return jdbc.sql("select tenant_id, name, status from tenant where tenant_id = :id")
                .param("id", tenantId)
                .query((rs, n) -> new TenantState(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("name"),
                        "ACTIVE".equals(rs.getString("status"))))
                .optional();
    }

    @Override
    public List<TenantOption> activeTenants() {
        return jdbc.sql("select tenant_id, name from tenant where status = 'ACTIVE' order by name, tenant_id")
                .query((rs, n) -> new TenantOption(rs.getObject("tenant_id", UUID.class), rs.getString("name")))
                .list();
    }

    @Override
    public Optional<String> walletOf(UUID userId) {
        return jdbc.sql("select address from wallet where user_id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public long countActiveGuests() {
        Long count = jdbc.sql("select count(*) from app_user where role = 'BETA_USER' and status = 'ACTIVE'")
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }
}
