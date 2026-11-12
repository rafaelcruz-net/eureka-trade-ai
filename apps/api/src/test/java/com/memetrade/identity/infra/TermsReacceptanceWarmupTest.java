package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsDocument;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * u3-reacceptance, Passo 15 (Build and Test loop-back 1; tests written after the code; NFR3.2, NFR3.4, NFR2.5): where
 * the warm-up exists (api only, never in the worker nor in the migrate profile), the text it prepares and its failures,
 * without a database. {@code TermsReacceptanceWarmupIT} runs it against PostgreSQL.
 */
@Tag("u3-reacceptance")
@ExtendWith(OutputCaptureExtension.class)
class TermsReacceptanceWarmupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(CurrentTerms.class, () -> terms("v2"))
            .withUserConfiguration(TermsReacceptanceWarmup.class);

    @Test
    void existsInTheApiProcess() {
        runner.run(context -> assertThat(context).hasSingleBean(TermsReacceptanceWarmup.class));
        runner.withPropertyValues("memetrade.process=api")
                .run(context -> assertThat(context).hasSingleBean(TermsReacceptanceWarmup.class));
    }

    @Test
    void neverExistsInTheWorker() {
        runner.withPropertyValues("memetrade.process=worker")
                .run(context -> assertThat(context).doesNotHaveBean(TermsReacceptanceWarmup.class));
    }

    @Test
    void neverExistsWhereTheStartUpJobsAreOffAsInTheMigrateProfile() {
        runner.withPropertyValues("memetrade.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TermsReacceptanceWarmup.class));
    }

    @Test
    void preparesTheTextOfTheLookupWithItsThreeParameters() {
        String text = TermsReacceptanceWarmup.JDBC_SQL;

        assertThat(text).doesNotContain(":user", ":version", ":tenant");
        assertThat(text.chars().filter(c -> c == '?').count()).isEqualTo(3);
        assertThat(text.replace("?", ""))
                .isEqualTo(JdbcTermsAcceptanceLookup.SQL
                        .replace(":user", "")
                        .replace(":version", "")
                        .replace(":tenant", ""));
        assertThat(TermsReacceptanceWarmup.EXECUTIONS_PER_CONNECTION)
                .as("more than the prepare threshold of pgjdbc (5)")
                .isGreaterThan(5);
    }

    @Test
    void keepsNoStateButItsDependencies() {
        assertThat(Arrays.stream(TermsReacceptanceWarmup.class.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(field -> field.getType().getSimpleName()))
                .containsExactlyInAnyOrder("DataSource", "CurrentTerms");
    }

    @Test
    void aDatabaseFailureBecomesOneWarningWithTheClassOnlyAndNeverStopsTheStart(CapturedOutput output)
            throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection to db.internal:5432 refused"));

        TermsReacceptanceWarmup warmup = new TermsReacceptanceWarmup(broken, terms("v2"));

        assertThatCode(warmup::onReady).doesNotThrowAnyException();
        assertThat(warmup.warmUp()).isZero();
        assertThat(output.getOut())
                .contains("WARN")
                .contains("Warm-up of the reacceptance lock query failed (SQLException)")
                .doesNotContain("db.internal");
    }

    @Test
    void aDataSourceThatIsNotAHikariPoolIsWarmedOnOneConnection(CapturedOutput output) throws Exception {
        DataSource plain = mock(DataSource.class);
        when(plain.isWrapperFor(com.zaxxer.hikari.HikariDataSource.class)).thenReturn(false);
        when(plain.getConnection()).thenThrow(new SQLException("refused"));

        assertThat(new TermsReacceptanceWarmup(plain, terms("v2")).warmUp()).isZero();
        assertThat(output.getOut()).contains("(SQLException)");
    }

    @Test
    void aCatalogFailureIsLoggedAndTheDatabaseIsNeverTouched(CapturedOutput output) {
        DataSource dataSource = mock(DataSource.class);
        CurrentTerms broken = new CurrentTerms() {
            @Override
            public TermsDocument current() {
                throw new IllegalStateException("catalog at /run/secrets/terms unreadable");
            }

            @Override
            public Optional<TermsDocument> find(String version) {
                return Optional.empty();
            }
        };

        assertThat(new TermsReacceptanceWarmup(dataSource, broken).warmUp()).isZero();
        verifyNoInteractions(dataSource);
        assertThat(output.getOut())
                .contains("Warm-up of the reacceptance lock query failed (IllegalStateException)")
                .doesNotContain("/run/secrets");
    }

    @Test
    void anInterruptedStartLogsTheInterruptionAndKeepsTheFlag(CapturedOutput output) throws Exception {
        DataSource slow = mock(DataSource.class);
        when(slow.isWrapperFor(com.zaxxer.hikari.HikariDataSource.class)).thenReturn(false);
        when(slow.getConnection()).thenAnswer(invocation -> {
            throw new SQLException("refused");
        });
        Thread.currentThread().interrupt();
        try {
            int warmed = new TermsReacceptanceWarmup(slow, terms("v2")).warmUp();

            assertThat(warmed).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(output.getOut()).contains("Warm-up of the reacceptance lock query failed (");
        } finally {
            Thread.interrupted();
        }
    }

    private static CurrentTerms terms(String version) {
        return new CurrentTerms() {
            @Override
            public TermsDocument current() {
                return new TermsDocument(version, "Termos de teste.", "Aviso de risco de teste.");
            }

            @Override
            public Optional<TermsDocument> find(String wanted) {
                return version.equals(wanted) ? Optional.of(current()) : Optional.empty();
            }
        };
    }
}
