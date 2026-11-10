package com.memetrade.riskcontrol.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.memetrade.testsupport.AbstractU6IT;
import org.junit.jupiter.api.Test;

/**
 * Passo 4 of U6 (written before the change of {@code 10-roles.sql}): the bootstrap creates the fixed group role
 * {@code risk_retention} without login, grants it only to the retention login, keeps the owner and the application out
 * of it, gives it USAGE on the schema, and stays idempotent without restoring privileges the migration revoked
 * (security-design.md, infrastructure-specification.md "Preparo dos papéis").
 */
class RolesBootstrapIT extends AbstractU6IT {

    @Test
    void theRiskRetentionGroupExistsWithoutLoginAndOnlyTheRetentionLoginIsAMember() {
        assertThat(jdbc.sql("select rolcanlogin from pg_roles where rolname = 'risk_retention'")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(member(RETENTION.user())).isTrue();
        assertThat(member(APP.user())).isFalse();
        assertThat(member(OWNER.user())).isFalse();
        // The retention login keeps the group of U2 as well.
        assertThat(connect(SUPERUSER)
                        .sql("select pg_has_role(:r, 'audit_retention', 'MEMBER')")
                        .param("r", RETENTION.user())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void theRiskRetentionGroupHasUsageOnTheSchemaButNoDdl() {
        assertThat(connect(SUPERUSER)
                        .sql("select has_schema_privilege('risk_retention', 'public', 'USAGE')")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(connect(SUPERUSER)
                        .sql("select has_schema_privilege('risk_retention', 'public', 'CREATE')")
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void theBootstrapRunsTwiceWithoutErrorAndNeverRestoresWhatTheMigrationRevoked() {
        assertThatCode(() -> {
                    bootstrap("memetrade");
                    bootstrap("memetrade");
                })
                .doesNotThrowAnyException();

        assertThat(member(RETENTION.user())).isTrue();
        assertThat(member(APP.user())).isFalse();
        assertThat(privilege(APP.user(), "order_evaluation", "DELETE")).isFalse();
        assertThat(privilege(APP.user(), "order_evaluation", "UPDATE")).isFalse();
        assertThat(privilege(APP.user(), "order_evaluation", "INSERT")).isTrue();
        assertThat(privilege(APP.user(), "risk_purge_run", "INSERT")).isFalse();
        assertThat(privilege("risk_retention", "order_evaluation", "DELETE")).isTrue();
        assertThat(privilege("risk_retention", "risk_purge_run", "INSERT")).isTrue();
        assertThat(privilege("risk_retention", "rejection_reason_detail", "SELECT"))
                .isFalse();
    }

    /**
     * u5-anonymization (security-design.md NFR2.3): after two runs, feedback_retention has the retention login and the
     * schema owner (for the function of migration 0015) and never the application; a membership granted to the
     * application by hand is revoked by the next run.
     */
    @Test
    void theFeedbackRetentionGroupHasTheOwnerAndTheRetentionLoginButNeverTheApplication() {
        bootstrap("memetrade");
        bootstrap("memetrade");

        assertThat(member(RETENTION.user(), "feedback_retention")).isTrue();
        assertThat(member(OWNER.user(), "feedback_retention")).isTrue();
        assertThat(member(APP.user(), "feedback_retention")).isFalse();

        connect(SUPERUSER).sql("grant feedback_retention to " + APP.user()).update();
        bootstrap("memetrade");

        assertThat(member(APP.user(), "feedback_retention")).isFalse();
        assertThat(member(OWNER.user(), "feedback_retention")).isTrue();
        assertThat(member(OWNER.user(), "risk_retention")).isFalse();
        assertThat(member(OWNER.user(), "audit_retention")).isFalse();
    }

    private static boolean member(String login, String role) {
        return connect(SUPERUSER)
                .sql("select pg_has_role(:r, :g, 'MEMBER')")
                .param("r", login)
                .param("g", role)
                .query(Boolean.class)
                .single();
    }

    @Test
    void theApplicationGroupMembershipIsRevokedByTheBootstrap() {
        connect(SUPERUSER).sql("grant risk_retention to " + APP.user()).update();
        assertThat(member(APP.user())).isTrue();

        bootstrap("memetrade");

        assertThat(member(APP.user())).isFalse();
    }

    private static boolean member(String login) {
        return connect(SUPERUSER)
                .sql("select pg_has_role(:r, 'risk_retention', 'MEMBER')")
                .param("r", login)
                .query(Boolean.class)
                .single();
    }

    private static boolean privilege(String role, String table, String privilege) {
        return connect(SUPERUSER)
                .sql("select has_table_privilege(:r, :t, :p)")
                .param("r", role)
                .param("t", table)
                .param("p", privilege)
                .query(Boolean.class)
                .single();
    }
}
