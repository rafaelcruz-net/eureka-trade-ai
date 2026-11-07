package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.OwnTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The prepared forms of the trail reading use the keyset indexes without a separate sort (BR2.3, BR2.4, NFR3.1), on a
 * database of its own ({@code u2_plan_*}) prepared like the shared one by {@link OwnTestDatabase}.
 *
 * <p>Regression of Build and Test loop-back 1 (failure F1): in the full suite the check ran on the shared database,
 * whose planner statistics on {@code audit_event} depend on what every other class wrote, and the trail cannot be
 * cleaned afterwards (only the retention login deletes, and only events older than 12 months). There the planner read
 * the form with {@code action} through {@code audit_event_time_id_idx} with a {@code Filter}. Here the seed is fixed
 * (same rows, same event ids on every run) and reproduces that condition inside this database: one dominant action
 * holds most of the window, next to the queried action and a few others, with fresh statistics before every plan.
 */
@Tag("u2")
class AuditEventReaderPlanIT {

    private static final String PREFIX = "u2_plan_";

    /** The window of the plans: one day of 2024, older than 12 months, as the other reading tests use. */
    private static final Instant FROM = Instant.parse("2024-03-01T00:00:00Z");

    private static final Instant TO = FROM.plus(Duration.ofDays(1));

    private static final String QUERIED_ACTION = "PLAN_ACTION";

    private static final int QUERIED_IN_WINDOW = 50;
    private static final int DOMINANT_IN_WINDOW = 20_000;
    private static final int EACH_OTHER_IN_WINDOW = 500;
    private static final List<String> OTHER_ACTIONS = List.of("PLAN_LOGIN", "PLAN_QUERY", "PLAN_EXPORT", "PLAN_PURGE");
    private static final int DAYS_BEFORE_WINDOW = 30;
    private static final int PER_DAY_BEFORE_WINDOW = 1_000;

    @BeforeAll
    static void seedAFixedVolumeAndAnalyze() {
        JdbcClient app = OwnTestDatabase.connect(PREFIX, AbstractPostgresIT.APP);
        long already = app.sql("select count(*) from audit_event where action like 'PLAN\\_%'")
                .query(Long.class)
                .single();
        if (already == 0) {
            // Inside the window: the queried action, one dominant action and a few others, spread over the day.
            seed(app, "PLAN_DOMINANT", DOMINANT_IN_WINDOW, FROM, TO);
            seed(app, QUERIED_ACTION, QUERIED_IN_WINDOW, FROM, TO);
            for (String other : OTHER_ACTIONS) {
                seed(app, other, EACH_OTHER_IN_WINDOW, FROM, TO);
            }
            // Before the window: the days the time bounds of the query must skip.
            Instant before = FROM.minus(Duration.ofDays(DAYS_BEFORE_WINDOW));
            seed(app, "PLAN_DOMINANT", DAYS_BEFORE_WINDOW * PER_DAY_BEFORE_WINDOW, before, FROM);
        }
        OwnTestDatabase.connect(PREFIX, AbstractPostgresIT.OWNER)
                .sql("analyze audit_event")
                .update();

        long inWindow = app.sql("select count(*) from audit_event"
                        + " where occurred_at >= cast(:from as timestamptz) and occurred_at < cast(:to as timestamptz)")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .query(Long.class)
                .single();
        assertThat(inWindow)
                .isEqualTo(DOMINANT_IN_WINDOW + QUERIED_IN_WINDOW + OTHER_ACTIONS.size() * EACH_OTHER_IN_WINDOW);
    }

    @Test
    void runsOnADatabaseOfItsOwn() {
        String database = OwnTestDatabase.connect(PREFIX, AbstractPostgresIT.APP)
                .sql("select current_database()")
                .query(String.class)
                .single();
        assertThat(database).startsWith(PREFIX);
    }

    @Test
    void bothPreparedFormsReadFromTheIndexWithoutASeparateSort() throws SQLException {
        for (boolean withAction : new boolean[] {false, true}) {
            for (boolean withCursor : new boolean[] {false, true}) {
                String plan = explain(JdbcAuditEventReader.sql(withAction, withCursor));
                assertThat(plan)
                        .as("action=%s cursor=%s", withAction, withCursor)
                        .contains(withAction ? "audit_event_action_time_id_idx" : "audit_event_time_id_idx")
                        .doesNotContain("Sort");
            }
        }
    }

    /**
     * Rows of {@code action} spread evenly over {@code [from, to)}, with event ids derived from the action and the row
     * number (a fixed seed: the same rows on every run). Set-based, with the APPLICATION login, as the publisher writes.
     */
    private static void seed(JdbcClient app, String action, int rows, Instant from, Instant to) {
        long stepMicros = Duration.between(from, to).toNanos() / 1_000 / rows;
        app.sql("""
                        insert into audit_event (event_id, tenant_id, actor, actor_type, action, correlation_id,
                                                 occurred_at, recorded_at, details)
                        select cast(md5(:seed || ':' || i) as uuid), null, 'system', 'SYSTEM', :action, 'corr-plan',
                               cast(:from as timestamptz) + (i * :step) * interval '1 microsecond',
                               cast(:from as timestamptz) + (i * :step) * interval '1 microsecond',
                               cast('{}' as jsonb)
                        from generate_series(0, :rows - 1) i
                        """)
                .params(Map.of(
                        "seed", action + "@" + from,
                        "action", action,
                        "from", from.toString(),
                        "step", stepMicros,
                        "rows", rows))
                .update();
    }

    /**
     * Plans the statement with literal values, in a transaction of its own that is rolled back; enable_sort=off makes
     * the planner prefer any plan that already delivers the order, so a remaining Sort node means the index cannot
     * provide it.
     */
    private static String explain(String sql) throws SQLException {
        String literal = sql.replace(":from", "timestamptz '" + FROM + "'")
                .replace(":to", "timestamptz '" + TO + "'")
                .replace(":action", "'" + QUERIED_ACTION + "'")
                .replace(":cursorAt", "timestamptz '" + FROM.plus(Duration.ofHours(12)) + "'")
                .replace(":cursorId", "'00000000-0000-4000-8000-000000000000'::uuid")
                .replace(":rows", "51");
        try (Connection connection = DriverManager.getConnection(
                        OwnTestDatabase.url(PREFIX), AbstractPostgresIT.APP.user(), AbstractPostgresIT.APP.password());
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("set local enable_sort = off");
                statement.execute("set local enable_seqscan = off");
                statement.execute("set local enable_bitmapscan = off");
                List<String> lines = new ArrayList<>();
                try (ResultSet plan = statement.executeQuery("explain " + literal)) {
                    while (plan.next()) {
                        lines.add(plan.getString(1));
                    }
                }
                return String.join("\n", lines);
            } finally {
                connection.rollback();
            }
        }
    }
}
