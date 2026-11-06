package com.memetrade.administration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 4 of U8 (infrastructure-specification.md "Privilégios no banco", NFR4.1, BR2.9, BR6.7), with the APPLICATION
 * login of the tests ({@code jdbc}) and the retention login: the history of the kill switch is append-only, its state
 * keeps one row, a comment is never edited and only the group role {@code feedback_retention} deletes it.
 */
class U8PrivilegesIT extends AbstractU8IT {

    @Test
    void theKillSwitchHistoryIsAppendOnlyForTheApplication() {
        UUID change = insertChange();

        assertRefused(() -> jdbc.sql("update kill_switch_change set reason = 'x' where change_id = :id")
                .param("id", change)
                .update());
        assertRefused(() -> jdbc.sql("delete from kill_switch_change where change_id = :id")
                .param("id", change)
                .update());
        assertRefused(() -> jdbc.sql("truncate kill_switch_change").update());
        assertThat(jdbc.sql("select count(*) from kill_switch_change where change_id = :id")
                        .param("id", change)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theRetentionLoginCannotTouchTheHistoryEither() {
        UUID change = insertChange();

        assertRefused(() -> connect(RETENTION)
                .sql("delete from kill_switch_change where change_id = :id")
                .param("id", change)
                .update());
    }

    @Test
    void theStateKeepsExactlyOneRowButCanBeUpdated() {
        assertRefused(() -> jdbc.sql("insert into kill_switch_state (id, state) values (1, 'ON')")
                .update());
        assertRefused(() -> jdbc.sql("delete from kill_switch_state").update());
        assertRefused(() -> jdbc.sql("truncate kill_switch_state").update());
        assertThatCode(() -> jdbc.sql("update kill_switch_state set pending_bots = 0 where id = 1")
                        .update())
                .doesNotThrowAnyException();
        assertThat(jdbc.sql("select count(*) from kill_switch_state")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aCommentIsNeverEditedNorDeletedByTheApplication() {
        UUID feedback = insertFeedback("Comentário de teste", Instant.parse("2026-10-01T00:00:00Z"));

        assertRefused(() -> jdbc.sql("update feedback_entry set text = 'outro' where feedback_id = :id")
                .param("id", feedback)
                .update());
        assertRefused(() -> jdbc.sql("delete from feedback_entry where feedback_id = :id")
                .param("id", feedback)
                .update());
        assertRefused(() -> jdbc.sql("truncate feedback_entry").update());
    }

    @Test
    void onlyTheRetentionRoleDeletesAComment() {
        UUID feedback = insertFeedback("Apagado pela retenção", Instant.parse("2026-01-01T00:00:00Z"));
        JdbcClient retention = connect(RETENTION);

        int deleted = retention
                .sql("delete from feedback_entry where feedback_id = :id")
                .param("id", feedback)
                .update();

        assertThat(deleted).isEqualTo(1);
        assertRefused(() -> retention
                .sql("update feedback_entry set text = 'x' where feedback_id = :id")
                .param("id", insertFeedback("Outro", Instant.parse("2026-01-01T00:00:00Z")))
                .update());
    }

    @Test
    void theRetentionGroupRoleHasNoLoginAndOnlyTheRetentionLoginIsAMember() {
        assertThat(jdbc.sql("select rolcanlogin from pg_roles where rolname = 'feedback_retention'")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(member(RETENTION.user())).isTrue();
        assertThat(member(APP.user())).isFalse();
        assertThat(member(OWNER.user())).isFalse();
    }

    @Test
    void theTableChecksRefuseAnUnknownScreenAndATextOutsideOneToTwoThousandCodePoints() {
        assertRefusedByCheck(() -> insertFeedback("A1", "texto", Instant.now()));
        assertRefusedByCheck(() -> insertFeedback("S1", "   ", Instant.now()));
        assertRefusedByCheck(() -> insertFeedback("S1", "a".repeat(2001), Instant.now()));
        // 2000 code points made of characters outside the BMP (4 bytes each in UTF-8) are accepted.
        assertThatCode(() -> insertFeedback("S9", "😀".repeat(2000), Instant.now()))
                .doesNotThrowAnyException();
    }

    private boolean member(String login) {
        return jdbc.sql("select pg_has_role(:login, 'feedback_retention', 'MEMBER')")
                .param("login", login)
                .query(Boolean.class)
                .single();
    }

    private UUID insertChange() {
        TestAccounts.Seeded admin = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID id = UUID.randomUUID();
        // Far above any version the service writes (it follows kill_switch_state.version), so never a collision.
        long version = jdbc.sql("select 1000000000000 + floor(random() * 1000000000000)::bigint")
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into kill_switch_change (change_id, version, from_state, to_state, admin_user_id, reason,
                            changed_at, correlation_id)
                        values (:id, :version, 'OFF', 'ON', :admin, 'teste de privilégio', now(), 'u8-privileges')
                        """)
                .param("id", id)
                .param("version", version)
                .param("admin", admin.userId())
                .update();
        return id;
    }

    private UUID insertFeedback(String text, Instant at) {
        return insertFeedback("S3", text, at);
    }

    private UUID insertFeedback(String screen, String text, Instant at) {
        TestAccounts.Seeded guest = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                        values (:id, :tenant, :user, :screen, :text, :at)
                        """)
                .param("id", id)
                .param("tenant", guest.tenantId())
                .param("user", guest.userId())
                .param("screen", screen)
                .param("text", text)
                .param("at", Timestamp.from(at))
                .update();
        return id;
    }

    /** Refused by a trigger of 0011 or by the missing privilege; the database message names the rule, never a value. */
    private static void assertRefused(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageMatching(
                        "(?s).*(append-only|never edited|exactly one row|feedback retention role|permission denied).*");
    }

    private static void assertRefusedByCheck(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("feedback_entry_");
    }
}
