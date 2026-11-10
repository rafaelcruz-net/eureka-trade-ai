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
 * Passo 6 of u4-data-requests (NFR5.1; reliability-design.md; infrastructure-specification.md): the migration of the
 * Unit only adds. Outside the {@code --rollback} lines it has no {@code UPDATE}, {@code DELETE}, {@code DROP},
 * {@code TRUNCATE} nor {@code ALTER TABLE} of an existing table; every changeset declares its rollback; it creates the
 * three tables and the indexes of the design (the unique partial index of the open requests included); the master
 * changelog includes it after 0013.
 */
@Tag("u4-data-requests")
class DataRequestsMigrationTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/changelog/changes/0014-data-requests.sql");
    private static final Path MASTER = Path.of("src/main/resources/db/changelog/db.changelog-master.yaml");
    private static final Pattern DESTRUCTIVE = Pattern.compile("\\b(UPDATE|DELETE|DROP|TRUNCATE|ALTER\\s+TABLE)\\b");

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
    void theMigrationCreatesTheThreeTablesOfTheDesign() throws IOException {
        String sql = read(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE data_subject_request (")
                .contains("CREATE TABLE data_subject_verification (")
                .contains("CREATE TABLE privacy_job_run (")
                .contains("CONSTRAINT privacy_job_run_purge_ck CHECK (purge_id IN ('dataSubjectRequestPurge'))");
    }

    @Test
    void theIndexesOfTheDesignExistWithTheUniquePartialIndexOfTheOpenRequests() throws IOException {
        String sql = read(MIGRATION);

        assertThat(sql)
                .contains("CREATE INDEX data_subject_request_person_idx ON data_subject_request (tenant_id, user_id,"
                        + " received_at)")
                .contains("CREATE INDEX data_subject_request_status_due_idx ON data_subject_request (status, due_at)")
                .contains("CREATE INDEX data_subject_request_closed_idx ON data_subject_request (closed_at) WHERE"
                        + " closed_at IS NOT NULL")
                .contains("CREATE UNIQUE INDEX uq_open_request_per_type ON data_subject_request (tenant_id, user_id,"
                        + " type)\n    WHERE status IN ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS') AND user_id"
                        + " IS NOT NULL;")
                .contains("CREATE INDEX data_subject_verification_pending_idx ON data_subject_verification"
                        + " (expires_at) WHERE state = 'PENDING'");
    }

    @Test
    void theSignatureHasNoColumn() throws IOException {
        assertThat(read(MIGRATION)
                        .lines()
                        .filter(line -> !line.strip().startsWith("--"))
                        .map(line -> line.toLowerCase(Locale.ROOT))
                        .filter(line -> line.contains("signature")))
                .as("BR4.12: the signature is never stored")
                .isEmpty();
    }

    @Test
    void theMasterChangelogIncludesItAfterTheMigrationOfU3() throws IOException {
        String master = read(MASTER);

        assertThat(master).contains("db/changelog/changes/0014-data-requests.sql");
        assertThat(master.indexOf("0014-data-requests.sql")).isGreaterThan(master.indexOf("0013-reacceptance.sql"));
    }

    @Test
    void theCheckFindsAStatementThatRewritesOrAltersAnExistingTable() {
        String bad = """
                --liquibase formatted sql
                --changeset memetrade:bad
                -- a comment that mentions drop is prose
                ALTER TABLE app_user ADD COLUMN x int;
                update data_subject_request set status = 'DONE';
                --rollback DROP TABLE x;
                """;

        assertThat(destructiveStatements(bad))
                .containsExactly(
                        "ALTER TABLE app_user ADD COLUMN x int;", "update data_subject_request set status = 'DONE';");
    }

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
