package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 10 of u2-retention (NFR5.1, BR2.14, reliability-design.md): the migration of the Unit only adds. Outside the
 * {@code --rollback} lines it has no statement that rewrites or removes data or objects ({@code UPDATE},
 * {@code DELETE}, {@code DROP}, {@code TRUNCATE}); every changeset declares its rollback; the master changelog
 * includes it after 0011. Comment lines are prose and are not statements.
 */
@Tag("u2-retention")
class RetentionMigrationTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/changelog/changes/0012-retention.sql");
    private static final Path MASTER = Path.of("src/main/resources/db/changelog/db.changelog-master.yaml");
    private static final Pattern DESTRUCTIVE = Pattern.compile("\\b(UPDATE|DELETE|DROP|TRUNCATE)\\b");

    @Test
    void theMigrationOnlyAddsOutsideItsRollbacks() throws IOException {
        assertThat(destructiveStatements(read(MIGRATION))).isEmpty();
    }

    @Test
    void everyChangesetDeclaresItsRollback() throws IOException {
        List<String> changesets = changesets(read(MIGRATION));

        assertThat(changesets).hasSize(4);
        assertThat(changesets).allSatisfy(changeset -> assertThat(changeset).contains("\n--rollback "));
    }

    @Test
    void theMigrationAddsTheColumnTheTableAndTheTwoIndexesOfTheDesign() throws IOException {
        String sql = read(MIGRATION);

        assertThat(sql)
                .contains("ALTER TABLE audit_event ADD COLUMN wallet_hmac varchar(64) NULL")
                .contains(
                        "CREATE INDEX audit_event_wallet_hmac_idx ON audit_event (wallet_hmac) WHERE wallet_hmac IS NOT NULL")
                .contains("CREATE TABLE identity_job_run")
                .contains("CREATE INDEX user_session_end_idx ON user_session "
                        + "((coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at))))");
    }

    @Test
    void theMasterChangelogIncludesItAfterTheMigrationOfU8() throws IOException {
        String master = read(MASTER);

        assertThat(master).contains("db/changelog/changes/0012-retention.sql");
        assertThat(master.indexOf("0012-retention.sql")).isGreaterThan(master.indexOf("0011-admin-feedback.sql"));
    }

    @Test
    void theCheckFindsAStatementThatRewritesOrRemoves() {
        String bad = """
                --liquibase formatted sql
                --changeset memetrade:bad
                -- a comment that mentions delete is prose
                UPDATE audit_event SET actor = 'x';
                delete from login_challenge;
                ALTER TABLE x Drop COLUMN y;
                --rollback DROP TABLE x;
                """;

        assertThat(destructiveStatements(bad))
                .containsExactly(
                        "UPDATE audit_event SET actor = 'x';",
                        "delete from login_challenge;",
                        "ALTER TABLE x Drop COLUMN y;");
    }

    /** Lines that are statements (not comments, so not the rollback lines) and rewrite or remove something. */
    static List<String> destructiveStatements(String sql) {
        List<String> found = new ArrayList<>();
        for (String line : sql.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            if (DESTRUCTIVE.matcher(trimmed.toUpperCase(Locale.ROOT)).find()) {
                found.add(trimmed);
            }
        }
        return found;
    }

    private static List<String> changesets(String sql) {
        List<String> changesets = new ArrayList<>();
        String[] parts = sql.split("\n--changeset ");
        for (int index = 1; index < parts.length; index++) {
            changesets.add("--changeset " + parts[index]);
        }
        return changesets;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
