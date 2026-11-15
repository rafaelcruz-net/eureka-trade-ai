package com.memetrade.txsigner.testsupport;

import com.memetrade.txsigner.domain.kms.KmsPort;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base of the TxSigner integration tests: real PostgreSQL 17 (same image as the Compose) with the schema
 * {@code txsigner} and the application login {@code txsigner_app}; the changelog applied by Liquibase with the owner
 * login, and the context connected with the application login, as in the Compose. The KMS is the in-memory simulator
 * (NFR8.4: no real KMS in tests). Each test creates its own data and never depends on the order.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "management.server.port=0",
            "txsigner.jobs.enabled=false",
            "txsigner.credential.current=" + AbstractTxSignerIT.TOKEN,
            "txsigner.credential.previous=" + AbstractTxSignerIT.PREVIOUS_TOKEN
        })
@AutoConfigureMockMvc
@Import(AbstractTxSignerIT.SimulatedKms.class)
public abstract class AbstractTxSignerIT {

    /** Throw-away test tokens; real tokens come only from the environment or the secrets manager. */
    public static final String TOKEN = "test-only-current-service-token";

    public static final String PREVIOUS_TOKEN = "test-only-previous-service-token";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("memetrade")
            .withUsername("txsigner_owner")
            .withPassword("owner-test-only");

    static {
        POSTGRES.start();
        try (Connection connection =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "txsigner_owner", "owner-test-only");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA txsigner AUTHORIZATION txsigner_owner");
            statement.execute("CREATE ROLE txsigner_app LOGIN PASSWORD 'app-test-only'");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Could not prepare the TxSigner test database", failure);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=txsigner");
        registry.add("spring.datasource.username", () -> "txsigner_app");
        registry.add("spring.datasource.password", () -> "app-test-only");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=txsigner");
        registry.add("spring.liquibase.user", () -> "txsigner_owner");
        registry.add("spring.liquibase.password", () -> "owner-test-only");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected KmsSimulator kms;

    /** Connection with the OWNER login, for checks the application login is not allowed to do. */
    protected static Connection ownerConnection() throws java.sql.SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "txsigner_owner", "owner-test-only");
        connection.setSchema("txsigner");
        return connection;
    }

    /** Connection with the APPLICATION login. */
    protected static Connection appConnection() throws java.sql.SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "txsigner_app", "app-test-only");
        connection.setSchema("txsigner");
        return connection;
    }

    @TestConfiguration
    public static class SimulatedKms {

        @Bean
        KmsSimulator kmsSimulator() {
            return new KmsSimulator();
        }

        @Bean
        @Primary
        KmsPort simulatedKmsPort(KmsSimulator simulator) {
            return simulator;
        }
    }
}
