package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.infra.AuditRetentionDataSource;
import com.memetrade.identity.api.Role;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AdminSessions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * NFR12.3 / step 2 of the U2 plan: the integration base runs the bootstrap, migrates with the owner and connects the
 * application and the retention pool with their own logins, while Liquibase stays off inside the API context.
 */
@Tag("u2")
class DatabaseLoginsIT extends AbstractPostgresIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AuditRetentionDataSource retention;

    @Autowired
    private AdminSessions adminSessions;

    @Test
    void theApiPoolConnectsWithTheApplicationLogin() {
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo(APP.user());
    }

    @Test
    void theRetentionPoolConnectsWithTheRetentionLogin() {
        assertThat(retention
                        .jdbc()
                        .sql("select current_user")
                        .query(String.class)
                        .single())
                .isEqualTo(RETENTION.user());
    }

    @Test
    void theApiContextDoesNotRunLiquibaseAndTheOwnerAppliedTheChangelog() {
        assertThat(context.getBeanNamesForType(liquibase.integration.spring.SpringLiquibase.class))
                .isEmpty();
        assertThat(jdbc.sql("select count(*) from databasechangelog")
                        .query(Integer.class)
                        .single())
                .isPositive();
        assertThat(jdbc.sql("select tableowner from pg_tables where tablename = 'databasechangelog'")
                        .query(String.class)
                        .single())
                .isEqualTo(OWNER.user());
    }

    @Test
    void theTestOnlyAdministratorSessionAuthenticatesAsAdminAndOtherTokensStillGoThroughTheRealCheck() {
        AdminSessions.AdminSession admin = adminSessions.openAdminSession();

        assertThat(adminSessions.authenticate(admin.token()))
                .hasValueSatisfying(
                        session -> assertThat(session.principal().role()).isEqualTo(Role.ADMIN));
        assertThat(adminSessions.authenticate("not-a-token")).isEmpty();
    }
}
