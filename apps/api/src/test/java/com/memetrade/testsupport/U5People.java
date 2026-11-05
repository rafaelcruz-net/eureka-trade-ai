package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Synthetic people of the U5 tests (never a real wallet nor personal data). */
public final class U5People {

    private U5People() {}

    public static Principal newPerson(JdbcClient jdbc) {
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        return new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER);
    }

    /** A second BETA_USER person in the SAME tenant as {@code other}. */
    public static Principal sameTenant(JdbcClient jdbc, Principal other) {
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                        insert into app_user (user_id, tenant_id, role, status, created_at)
                        values (:id, :tenant, 'BETA_USER', 'ACTIVE', now())
                        """).param("id", userId).param("tenant", other.tenantId()).update();
        return new Principal(other.tenantId(), userId, Role.BETA_USER);
    }
}
