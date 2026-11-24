package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U4, Etapa 7 (written before the migration; NFR2.7, NFR3.8, NFR8.6; reliability-design.md
 * "Migração 0026"): the rule of {@code bot_cycle.outcome} accepts the 12 values of today (what the previous image
 * writes) and the two new ones of the U4, and refuses anything else. The two files carry only the allowed commands (no
 * GRANT, REVOKE, INSERT, UPDATE nor DELETE); undoing and applying again the 0026 and the 0027 changes no row of
 * {@code bot_cycle}.
 */
class Migration0026IT extends AbstractPostgresIT {

    static final List<String> TODAY = List.of(
            "NO_ORDER",
            "FILLED",
            "NOT_FILLED",
            "REJECTED",
            "DUPLICATE",
            "CONFLICT",
            "REAL_RECORDED",
            "REAL_ORDER_IN_PROGRESS",
            "REAL_LIQUIDATION_ACTIVE",
            "REAL_BOT_NOT_OWNED",
            "REAL_REJECTED",
            "REAL_UNAVAILABLE");

    static final List<String> NEW = List.of("REAL_NO_OWN_QUANTITY", "REAL_OWN_QUANTITY_UNAVAILABLE");

    private static final Path CHANGES = Path.of("src", "main", "resources", "db", "changelog", "changes");

    @Test
    void theTwelveValuesOfTodayAndTheTwoNewOnesAreAcceptedAndNothingElse() {
        UUID bot = bot(jdbc);
        for (String outcome : TODAY) {
            assertThatCode(() -> cycle(jdbc, bot, outcome)).as(outcome).doesNotThrowAnyException();
        }
        for (String outcome : NEW) {
            assertThatCode(() -> cycle(jdbc, bot, outcome)).as(outcome).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> cycle(jdbc, bot, "REAL_SOLD_EVERYTHING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTwoFilesCarryOnlyTheAllowedCommands() throws Exception {
        String own =
                Files.readString(CHANGES.resolve("0026-bot-cycle-own-quantity-outcomes.sql"), StandardCharsets.UTF_8);
        String index = Files.readString(
                CHANGES.resolve("0027-real-decision-request-bot-pending-sell-idx.sql"), StandardCharsets.UTF_8);
        for (String sql : List.of(own, index)) {
            String code = sql.lines()
                    .filter(line -> !line.startsWith("--") || line.startsWith("--rollback"))
                    .map(line -> line.replace("--rollback", ""))
                    .reduce("", (a, b) -> a + "\n" + b)
                    .toUpperCase(Locale.ROOT);
            for (String forbidden : List.of("GRANT", "REVOKE", "INSERT ", "UPDATE ", "DELETE ")) {
                assertThat(Pattern.compile("\\b" + forbidden.trim() + "\\b")
                                .matcher(code)
                                .find())
                        .as(forbidden)
                        .isFalse();
            }
        }
        assertThat(own)
                .contains("--changeset memetrade:0026-bot-cycle-outcome-not-valid")
                .contains("--changeset memetrade:0026-bot-cycle-outcome-validate")
                .contains("NOT VALID")
                .contains("VALIDATE CONSTRAINT bot_cycle_outcome_ck")
                .doesNotContainPattern("(?im)^SET lock_timeout");
        assertThat(own.split("SET LOCAL lock_timeout = '5s';", -1)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(index)
                .contains("runInTransaction:false")
                .contains("DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;")
                .contains("CREATE INDEX CONCURRENTLY real_decision_request_bot_pending_sell_idx")
                .doesNotContain("IF NOT EXISTS");
    }

    @Test
    @SuppressWarnings("deprecation")
    void undoingAndApplyingAgainChangesNoRowOfBotCycle() throws Exception {
        String dbName = "u4mig_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("create database " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        JdbcClient app = connect(APP, url);
        UUID bot = bot(app);
        for (String outcome : TODAY) {
            cycle(app, bot, outcome);
        }
        long before = count(app);

        try (Connection connection = DriverManager.getConnection(url, OWNER.user(), OWNER.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase =
                    new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database);
            int changesets = AnonymizationMigrationIT.changesetsFrom("0026-bot-cycle-own-quantity-outcomes.sql");
            assertThat(changesets).isEqualTo(3);
            liquibase.rollback(changesets, "");
        }
        assertThat(count(app)).isEqualTo(before);
        assertThatThrownBy(() -> cycle(app, bot, "REAL_NO_OWN_QUANTITY"))
                .as("the rule of today is back")
                .isInstanceOf(DataIntegrityViolationException.class);

        migrate(url);

        assertThat(count(app)).isEqualTo(before);
        assertThatCode(() -> cycle(app, bot, "REAL_OWN_QUANTITY_UNAVAILABLE")).doesNotThrowAnyException();
        assertThat(connect(OWNER, url)
                        .sql("select convalidated from pg_constraint where conname = 'bot_cycle_outcome_ck'")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    static UUID bot(JdbcClient client) {
        TestAccounts.Seeded person = TestAccounts.betaUser(client, SyntheticIds.mint());
        UUID bot = UUID.randomUUID();
        client.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        client.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, 'robo', 'MOMENTUM_VOLUME', '1', 'ACTIVE', now(), now(), 'REAL')""")
                .param("b", bot)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        return bot;
    }

    static void cycle(JdbcClient client, UUID bot, String outcome) {
        client.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status, outcome,
                          started_at, finished_at)
                        select gen_random_uuid(), bot_id, tenant_id, owner_user_id,
                          now() + (random() * interval '1000 days'), 'COMPLETED', :outcome, now(), now()
                        from bot where bot_id = :b""").param("b", bot).param("outcome", outcome).update();
    }

    private static long count(JdbcClient client) {
        return client.sql("select count(*) from bot_cycle").query(Long.class).single();
    }
}
