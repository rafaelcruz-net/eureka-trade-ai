package com.memetrade.testsupport;

import com.memetrade.ops.MigrationRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base class of every backend integration test, prepared exactly like the Docker Compose environment of U2:
 *
 * <ol>
 *   <li>real PostgreSQL 17 (same image as docker-compose.yml) with the U2 server parameters
 *       ({@link #SERVER_PARAMETERS}: commit timestamps on, no statement or parameter logging);
 *   <li>{@code infra/postgres/bootstrap/bootstrap.sh} run inside the container as the superuser, creating the
 *       owner, application and retention logins;
 *   <li>the Liquibase changelog applied by {@link MigrationRunner} with the OWNER login (the db-migrate path);
 *   <li>the Spring context connected with the APPLICATION login (Liquibase disabled, as in production) and the
 *       retention pool with the RETENTION login.
 * </ol>
 *
 * <p>Also a fixed injectable clock and no scheduled jobs (tests drive relays and jobs directly). Each test creates its
 * own data and never depends on another test or on execution order.
 */
@Tag("u1")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "memetrade.scheduling.enabled=false",
            "management.server.port=0",
            "memetrade.auth.access-token-signing-key=" + AbstractPostgresIT.TEST_SIGNING_KEY,
            "memetrade.auth.guest-app-origin=http://localhost:3000",
            // U4: tests walk many pages within one minute of the fixed clock; ScannerRateLimitIT checks the real 60.
            "memetrade.scanner.rate-limit-per-minute=10000"
        })
@Import(AbstractPostgresIT.FixedClockConfig.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
public abstract class AbstractPostgresIT {

    /** Same image as docker-compose.yml (ComposeFilesTest asserts the tag matches). */
    public static final String POSTGRES_IMAGE = "postgres:17-alpine";

    /** Test-only HMAC key (base64 of 48 non-secret bytes); production keys come only from .env. */
    public static final String TEST_SIGNING_KEY =
            "dGVzdC1vbmx5LWtleS1kby1ub3QtdXNlLWluLXByb2R1Y3Rpb24tMDEyMzQ1Njc4OQ==";

    /**
     * PostgreSQL server parameters of U2 (security-design.md, reliability-design.md). docker-compose.yml passes the
     * same list on the command line; ComposeFilesTest compares both and PostgresParametersIT reads them back.
     */
    public static final List<String> SERVER_PARAMETERS = List.of(
            "track_commit_timestamp=on",
            "log_statement=none",
            "log_min_duration_statement=-1",
            "log_min_error_statement=panic",
            "log_error_verbosity=terse",
            "log_parameter_max_length=0",
            "log_parameter_max_length_on_error=0");

    /** Throw-away test logins; production names and passwords come only from .env. */
    public static final Login SUPERUSER = new Login("memetrade_test", "memetrade_test");

    public static final Login OWNER = new Login("memetrade_owner", "owner-test-only");
    public static final Login APP = new Login("memetrade_app", "app-test-only");
    public static final Login RETENTION = new Login("memetrade_retention", "retention-test-only");

    private static final String BOOTSTRAP_DIR = "/bootstrap";

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("memetrade")
            .withUsername(SUPERUSER.user())
            .withPassword(SUPERUSER.password())
            .withCommand(Stream.concat(
                            // max_connections is test-only (not in SERVER_PARAMETERS, which docker-compose.yml shares):
                            // since U8 the full suite caches more Spring contexts, each with its own pools, than the
                            // default 100 slots hold.
                            Stream.of("postgres", "-c", "fsync=off", "-c", "max_connections=300"),
                            SERVER_PARAMETERS.stream().flatMap(p -> Stream.of("-c", p)))
                    .toArray(String[]::new))
            .withEnv("POSTGRES_HOST", "127.0.0.1")
            .withEnv("DB_OWNER_USER", OWNER.user())
            .withEnv("DB_OWNER_PASSWORD", OWNER.password())
            .withEnv("DB_USER", APP.user())
            .withEnv("DB_PASSWORD", APP.password())
            .withEnv("DB_RETENTION_USER", RETENTION.user())
            .withEnv("DB_RETENTION_PASSWORD", RETENTION.password())
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "..", "infra", "postgres", "bootstrap")), BOOTSTRAP_DIR);

    static {
        // Singleton container shared by all integration test classes of the JVM run.
        POSTGRES.start();
        bootstrap("memetrade");
        migrate(POSTGRES.getJdbcUrl());
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", APP::user);
        registry.add("spring.datasource.password", APP::password);
        registry.add("memetrade.audit.retention-db.username", RETENTION::user);
        registry.add("memetrade.audit.retention-db.password", RETENTION::password);
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private com.memetrade.identity.domain.ratelimit.LoginRateLimiter loginRateLimiter;

    /**
     * Every test starts at the same fixed instant and with empty login counters, whatever an earlier test did to the
     * shared clock and limiter (tests never depend on each other or on execution order).
     */
    @Autowired
    private com.memetrade.shared.web.PersonRouteRateLimiter scannerRateLimiter;

    /** U3: the invitation lookup limiter has its own counters, reset like the login ones. */
    @Autowired
    private com.memetrade.identity.domain.ratelimit.InviteLookupRateLimiter inviteLookupRateLimiter;

    @org.junit.jupiter.api.BeforeEach
    void resetSharedState() {
        clock.set(MutableClock.DEFAULT_START);
        loginRateLimiter.clear();
        scannerRateLimiter.clear();
        inviteLookupRateLimiter.clear();
    }

    /** Runs the versioned role bootstrap as the container superuser against {@code database}. */
    public static void bootstrap(String database) {
        try {
            Container.ExecResult result =
                    POSTGRES.execInContainer("env", "POSTGRES_DB=" + database, "sh", BOOTSTRAP_DIR + "/bootstrap.sh");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "bootstrap.sh failed (exit " + result.getExitCode() + "): " + result.getStderr());
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("bootstrap.sh could not be run", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bootstrap.sh was interrupted", interrupted);
        }
    }

    /**
     * Applies the changelog with the OWNER login through the production db-migrate entry point, without letting it
     * reinitialise the logging of the JVM. The migration tests ({@code db/*MigrationIT}) and {@link OwnTestDatabase}
     * migrate in the middle of the run: the db-migrate application would reset Logback with {@code root=WARN} and every
     * Spring context already cached would stop writing its INFO lines (Build and Test loop-back 1:
     * {@code AuditObservabilityIT} after {@code AuditEventReaderPlanIT}; Build and Test of 261109-termos-lgpd, first CI
     * run: {@code AnonymizationNoWalletLeftIT} and {@code AnonymizationObservabilityIT} after the {@code db} tests).
     * Regression tests: {@code OwnTestDatabaseIT}.
     */
    public static void migrate(String jdbcUrl) {
        String previous = System.getProperty(LoggingSystem.SYSTEM_PROPERTY);
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        int exit;
        try {
            exit = MigrationRunner.migrate(
                    "--spring.datasource.url=" + jdbcUrl,
                    "--DB_OWNER_USER=" + OWNER.user(),
                    "--DB_OWNER_PASSWORD=" + OWNER.password(),
                    "--logging.level.root=WARN");
        } finally {
            if (previous == null) {
                System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
            } else {
                System.setProperty(LoggingSystem.SYSTEM_PROPERTY, previous);
            }
        }
        if (exit != 0) {
            throw new IllegalStateException("MigrationRunner exited with " + exit);
        }
    }

    /**
     * JDBC URL of the shared container, started and migrated on first use; for tests that need their own data source
     * without a Spring context (u2-retention purge tests).
     */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /** A JDBC client for one login on the test database (one dedicated connection, auto-commit). */
    public static JdbcClient connect(Login login) {
        return connect(login, POSTGRES.getJdbcUrl());
    }

    public static JdbcClient connect(Login login, String jdbcUrl) {
        // One cached connection per login and database, so tests never exhaust max_connections.
        return CONNECTIONS.computeIfAbsent(
                login.user() + "@" + jdbcUrl,
                key -> JdbcClient.create(
                        new SingleConnectionDataSource(jdbcUrl, login.user(), login.password(), true)));
    }

    private static final java.util.Map<String, JdbcClient> CONNECTIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Database login used by the tests. */
    public record Login(String user, String password) {}

    @TestConfiguration(proxyBeanMethods = false)
    public static class FixedClockConfig {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }

        /** Test-only administrator sessions; replaces the production SessionService in integration tests. */
        @Bean
        @Primary
        public AdminSessions adminSessions(
                com.memetrade.identity.domain.session.SessionRepository sessions,
                com.memetrade.identity.domain.session.AccessTokens accessTokens,
                com.memetrade.identity.domain.session.RefreshTokens refreshTokens,
                com.memetrade.identity.domain.session.SessionSettings settings,
                com.memetrade.audit.api.AuditEventPublisher audit,
                java.time.Clock clock) {
            return new AdminSessions(sessions, accessTokens, refreshTokens, settings, audit, clock);
        }
    }
}
