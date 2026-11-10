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
 * Passo 4 of u3-reacceptance (NFR5.1, reliability-design.md): the migration of the Unit only adds or relaxes. Outside
 * the {@code --rollback} lines the only accepted "DROP" is {@code DROP NOT NULL}; {@code UPDATE}, {@code DELETE},
 * {@code TRUNCATE}, {@code DROP TABLE}, {@code DROP COLUMN}, {@code DROP CONSTRAINT} and {@code DROP INDEX} are refused.
 * Every changeset declares its rollback; the master changelog includes it after 0012; the REVOKE of 0007 is not undone.
 */
@Tag("u3-reacceptance")
class ReacceptanceMigrationTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/changelog/changes/0013-reacceptance.sql");
    private static final Path MASTER = Path.of("src/main/resources/db/changelog/db.changelog-master.yaml");
    private static final Pattern DESTRUCTIVE = Pattern.compile("\\b(UPDATE|DELETE|TRUNCATE|GRANT)\\b");
    private static final Pattern DROP = Pattern.compile("\\bDROP\\b(?!\\s+NOT\\s+NULL\\b)");

    @Test
    void theMigrationOnlyAddsOrRelaxesOutsideItsRollbacks() throws IOException {
        assertThat(refusedStatements(read(MIGRATION))).isEmpty();
    }

    @Test
    void everyChangesetDeclaresItsRollback() throws IOException {
        List<String> changesets = changesets(read(MIGRATION));

        assertThat(changesets).hasSize(5);
        assertThat(changesets).allSatisfy(changeset -> assertThat(changeset).contains("\n--rollback "));
    }

    @Test
    void theMigrationHasTheChangesOfTheDesign() throws IOException {
        String sql = read(MIGRATION);

        assertThat(sql)
                .contains("ALTER TABLE terms_acceptance ALTER COLUMN invite_id DROP NOT NULL;")
                .contains("ALTER TABLE terms_acceptance ADD COLUMN source varchar(16) NOT NULL DEFAULT 'INVITE';")
                .contains("CHECK (source IN ('INVITE', 'REACCEPTANCE'))")
                .contains("CHECK ((source = 'INVITE') = (invite_id IS NOT NULL))")
                .contains("ADD CONSTRAINT uq_terms_acceptance_user_version UNIQUE (user_id, terms_version);");
    }

    /** NFR2.3: the table stays insert only; the migration never grants anything back. */
    @Test
    void theMigrationNeverGrantsUpdateOrDeleteBack() throws IOException {
        String statements = String.join("\n", statements(read(MIGRATION))).toUpperCase(Locale.ROOT);

        assertThat(statements).doesNotContain("GRANT").doesNotContain("REVOKE");
    }

    @Test
    void theMasterChangelogIncludesItAfterTheMigrationOfU2() throws IOException {
        String master = read(MASTER);

        assertThat(master).contains("db/changelog/changes/0013-reacceptance.sql");
        assertThat(master.indexOf("0013-reacceptance.sql")).isGreaterThan(master.indexOf("0012-retention.sql"));
    }

    @Test
    void theCheckFindsAStatementThatRewritesOrRemovesButAcceptsDropNotNull() {
        String bad = """
                --liquibase formatted sql
                --changeset memetrade:bad
                -- a comment that mentions delete and drop table is prose
                ALTER TABLE terms_acceptance ALTER COLUMN invite_id DROP NOT NULL;
                UPDATE terms_acceptance SET source = 'X';
                delete from terms_acceptance;
                ALTER TABLE terms_acceptance Drop Constraint uq_terms_acceptance_invite;
                ALTER TABLE terms_acceptance DROP COLUMN source;
                DROP TABLE terms_acceptance;
                --rollback DROP TABLE x;
                """;

        assertThat(refusedStatements(bad))
                .containsExactly(
                        "UPDATE terms_acceptance SET source = 'X';",
                        "delete from terms_acceptance;",
                        "ALTER TABLE terms_acceptance Drop Constraint uq_terms_acceptance_invite;",
                        "ALTER TABLE terms_acceptance DROP COLUMN source;",
                        "DROP TABLE terms_acceptance;");
    }

    /** Lines that are statements (not comments, so not the rollback lines) and rewrite or remove something. */
    static List<String> refusedStatements(String sql) {
        List<String> found = new ArrayList<>();
        for (String statement : statements(sql)) {
            String upper = statement.toUpperCase(Locale.ROOT);
            if (DESTRUCTIVE.matcher(upper).find() || DROP.matcher(upper).find()) {
                found.add(statement);
            }
        }
        return found;
    }

    private static List<String> statements(String sql) {
        List<String> lines = new ArrayList<>();
        for (String line : sql.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                lines.add(trimmed);
            }
        }
        return lines;
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
