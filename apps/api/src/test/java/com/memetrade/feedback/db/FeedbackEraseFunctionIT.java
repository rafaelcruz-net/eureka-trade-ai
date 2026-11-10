package com.memetrade.feedback.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code (BR5.8; NFR2.3): after the role bootstrap ran twice, a direct
 * DELETE of the application login is still refused by the unchanged trigger, while the SECURITY DEFINER function
 * {@code erase_feedback_of_user} (owner member of feedback_retention, fixed search_path, EXECUTE only for the
 * application) erases exactly the comments of the person asked and returns how many.
 */
@Tag("u5-anonymization")
class FeedbackEraseFunctionIT extends AbstractPostgresIT {

    @BeforeAll
    static void bootstrapTwice() {
        bootstrap("memetrade");
        bootstrap("memetrade");
    }

    @Test
    void theDirectDeleteOfTheApplicationIsStillRefused() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID comment = insertFeedback(person);

        assertThatThrownBy(() -> jdbc.sql("delete from feedback_entry where feedback_id = :id")
                        .param("id", comment)
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThat(count(person.userId())).isEqualTo(1);
    }

    @Test
    void theFunctionErasesOnlyThePersonAskedAndReturnsTheCount() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        TestAccounts.Seeded other = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        insertFeedback(person);
        insertFeedback(person);
        insertFeedback(person);
        insertFeedback(other);

        Integer erased = erase(jdbc, person.tenantId(), person.userId());

        assertThat(erased).isEqualTo(3);
        assertThat(count(person.userId())).isZero();
        assertThat(count(other.userId())).isEqualTo(1);
    }

    @Test
    void thePersonOfAnotherTenantIsNeverErased() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        insertFeedback(person);

        Integer erased = erase(jdbc, UUID.randomUUID(), person.userId());

        assertThat(erased).isZero();
        assertThat(count(person.userId())).isEqualTo(1);
    }

    @Test
    void aPersonWithoutCommentsErasesNothing() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());

        assertThat(erase(jdbc, person.tenantId(), person.userId())).isZero();
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThatThrownBy(() -> erase(jdbc, null, UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> erase(jdbc, UUID.randomUUID(), null)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void onlyTheApplicationLoginMayExecuteIt() {
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, SyntheticIds.mint());

        assertThatThrownBy(() -> erase(connect(RETENTION), person.tenantId(), person.userId()))
                .isInstanceOf(DataAccessException.class);
        assertThat(executable("public")).isFalse();
        assertThat(executable(APP.user())).isTrue();
        assertThatCode(() -> erase(jdbc, person.tenantId(), person.userId())).doesNotThrowAnyException();
    }

    @Test
    void theFunctionIsSecurityDefinerOwnedByTheOwnerWithAFixedSearchPath() {
        var row = connect(SUPERUSER).sql("""
                        select p.prosecdef, r.rolname as owner, array_to_string(p.proconfig, ',') as config
                        from pg_proc p join pg_roles r on r.oid = p.proowner
                        where p.proname = 'erase_feedback_of_user'
                        """).query().singleRow();

        assertThat(row.get("prosecdef")).isEqualTo(true);
        assertThat(row.get("owner")).isEqualTo(OWNER.user());
        assertThat((String) row.get("config")).contains("search_path=public, pg_temp");
    }

    @Test
    void theOwnerIsAMemberOfTheRetentionRoleAndTheApplicationIsNot() {
        assertThat(member(OWNER.user())).isTrue();
        assertThat(member(RETENTION.user())).isTrue();
        assertThat(member(APP.user())).isFalse();
    }

    private static boolean member(String login) {
        return connect(SUPERUSER)
                .sql("select pg_has_role(:r, 'feedback_retention', 'MEMBER')")
                .param("r", login)
                .query(Boolean.class)
                .single();
    }

    private static boolean executable(String role) {
        String sql = "public".equals(role)
                ? "select bool_or(a.grantee = 0) from pg_proc p cross join lateral aclexplode(p.proacl) a"
                        + " where p.proname = 'erase_feedback_of_user' and a.privilege_type = 'EXECUTE'"
                : "select has_function_privilege(:r, 'erase_feedback_of_user(uuid, uuid)', 'EXECUTE')";
        var statement = connect(SUPERUSER).sql(sql);
        if (!"public".equals(role)) {
            statement = statement.param("r", role);
        }
        Boolean result = statement.query(Boolean.class).single();
        return Boolean.TRUE.equals(result);
    }

    private static Integer erase(JdbcClient client, UUID tenant, UUID user) {
        return client.sql("select erase_feedback_of_user(:t, :u)")
                .param("t", tenant, java.sql.Types.OTHER)
                .param("u", user, java.sql.Types.OTHER)
                .query(Integer.class)
                .single();
    }

    private UUID insertFeedback(TestAccounts.Seeded person) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                        values (:id, :t, :u, 'S2', 'Comentário sintético', :at)
                        """)
                .param("id", id)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("at", Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")))
                .update();
        return id;
    }

    private long count(UUID userId) {
        return jdbc.sql("select count(*) from feedback_entry where user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
    }
}
