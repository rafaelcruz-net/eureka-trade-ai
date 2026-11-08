package com.memetrade.riskcontrol.infra;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE (volta 2 of Build and Test, F10, NFR2.5): reads the cumulative statistics of
 * the two tables the purge plans over, for the seed of the scenario {@code u6}. It reads only the catalog
 * ({@code pg_stat_user_tables}, {@code pg_class}, server settings), never a row of U6, through the application login.
 * It lives in infra because the load package reaches the database only through U6 classes (ArchUnit rule). The seed
 * decides from these figures whether the statistics are fresh ({@code LoadtestRiskSeed.fresh}).
 */
@Component
@Profile("loadtest")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoadtestTableStatistics {

    /**
     * The statistics of one seeded table.
     *
     * @param modifiedSinceAnalyze {@code n_mod_since_analyze}
     * @param analyzeThreshold the modifications above which autovacuum analyzes the table again: server settings over
     *     the current reltuples, rounded down (neither table has storage parameters of its own)
     * @param lastAnalyzed the later of {@code last_analyze} and {@code last_autoanalyze}; empty when never analyzed
     */
    public record TableState(
            String table, long modifiedSinceAnalyze, long analyzeThreshold, Optional<Instant> lastAnalyzed) {

        public TableState {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(lastAnalyzed, "lastAnalyzed");
        }
    }

    private static final String SEEDED_TABLES = """
            select s.relname as table_name,
                   s.n_mod_since_analyze as modified,
                   floor(current_setting('autovacuum_analyze_threshold')::numeric
                       + current_setting('autovacuum_analyze_scale_factor')::numeric * greatest(c.reltuples, 0)::numeric)
                       ::bigint as threshold,
                   greatest(s.last_analyze, s.last_autoanalyze) as analyzed
            from pg_stat_user_tables s join pg_class c on c.oid = s.relid
            where s.relname in ('order_evaluation', 'order_rejection')
            order by s.relname
            """;

    private final JdbcClient jdbc;

    public LoadtestTableStatistics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether autovacuum runs on the server (otherwise nothing analyzes the seeded tables for the seed). */
    public boolean autovacuumOn() {
        return "on"
                .equals(jdbc.sql("select current_setting('autovacuum')")
                        .query(String.class)
                        .single());
    }

    /** The database now(), the clock of {@code last_autoanalyze}, so the seed compares like with like. */
    public Instant databaseNow() {
        return jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
    }

    /**
     * The statistics of {@code order_evaluation} and {@code order_rejection}; a table missing from the statistics is
     * missing from the list. One read per call in its own auto-commit transaction, so every poll sees current figures.
     */
    public List<TableState> seededTables() {
        return jdbc.sql(SEEDED_TABLES)
                .query((row, index) -> {
                    Timestamp analyzed = row.getTimestamp("analyzed");
                    return new TableState(
                            row.getString("table_name"),
                            row.getLong("modified"),
                            row.getLong("threshold"),
                            Optional.ofNullable(analyzed).map(Timestamp::toInstant));
                })
                .list();
    }
}
