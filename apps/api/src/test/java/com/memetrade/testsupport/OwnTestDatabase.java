package com.memetrade.testsupport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A database of its own in the shared PostgreSQL 17 container, for test classes whose result depends on the state of
 * the whole database (planner statistics, volume): created by the superuser, prepared by the versioned role bootstrap
 * and migrated by the OWNER through the production db-migrate path, exactly like {@link AbstractPostgresIT} prepares
 * the shared one. One database per prefix and JVM, created on first use; no other test class ever reads it.
 *
 * <p>The common part of the pattern of {@link AbstractU9MeasurementIT} and {@link U7VolumeDatabase}, kept as a helper
 * rather than a superclass: the U9 measurements must keep extending {@link AbstractU9IT}, and a plain JDBC test (such
 * as {@code AuditEventReaderPlanIT} of U2) needs no Spring context at all. The integration tests never depend on
 * shared state nor on execution order; a database of their own is how a volume or a planner check gets there.
 */
public final class OwnTestDatabase {

    private static final Map<String, String> URLS = new ConcurrentHashMap<>();

    private OwnTestDatabase() {}

    /**
     * The JDBC URL of the database of {@code prefix}, created and migrated on first use (once per JVM).
     *
     * @param prefix the name prefix (lower case, ending in an underscore), also used to recognise the database
     */
    public static String url(String prefix) {
        return URLS.computeIfAbsent(prefix, OwnTestDatabase::create);
    }

    /** The {@code login} on the database of {@code prefix} (one dedicated connection, auto-commit). */
    public static JdbcClient connect(String prefix, AbstractPostgresIT.Login login) {
        return AbstractPostgresIT.connect(login, url(prefix));
    }

    /**
     * Refreshes the planner statistics of the database of {@code prefix}, as autovacuum does in production after a
     * volume of inserts. Runs with the OWNER login, which owns every table of the migrations.
     */
    public static void analyze(String prefix) {
        connect(prefix, AbstractPostgresIT.OWNER).sql("analyze").update();
    }

    private static String create(String prefix) {
        String name = prefix + UUID.randomUUID().toString().replace("-", "");
        AbstractPostgresIT.connect(AbstractPostgresIT.SUPERUSER)
                .sql("create database " + name)
                .update();
        String created = AbstractPostgresIT.POSTGRES.getJdbcUrl().replace("/memetrade", "/" + name);
        AbstractPostgresIT.bootstrap(name);
        AbstractPostgresIT.migrate(created);
        return created;
    }
}
