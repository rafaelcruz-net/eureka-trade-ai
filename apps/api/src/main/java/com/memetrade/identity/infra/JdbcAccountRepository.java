package com.memetrade.identity.infra;

import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.session.Account;
import com.memetrade.identity.domain.session.AccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> findByWallet(String walletAddress) {
        return jdbc.sql("""
                        select u.user_id, u.tenant_id, u.role, u.status as user_status, t.status as tenant_status
                        from wallet w
                        join app_user u on u.user_id = w.user_id
                        join tenant t on t.tenant_id = u.tenant_id
                        where w.address = :address
                        """)
                .param("address", walletAddress)
                .query((rs, n) -> new Account(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        Role.valueOf(rs.getString("role")),
                        "ACTIVE".equals(rs.getString("user_status")),
                        "ACTIVE".equals(rs.getString("tenant_status"))))
                .optional();
    }
}
