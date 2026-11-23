package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.infra.MismatchAuditDataSource;
import com.memetrade.audit.infra.MismatchAuditDataSourceConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U2, Etapa 21 (infrastructure-specification.md "Pool só na API", "Pool fora da prontidão";
 * NFR4.4): the pool {@code pnl-mismatch-audit} exists only in the api process; the worker starts without it. Where it
 * exists it is neither a {@link DataSource} bean nor a {@link PlatformTransactionManager} bean, so the main pool, the
 * main manager and the readiness never see it.
 */
class MismatchAuditPoolContextIT {

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(DataSourceProperties.class, () -> {
                    DataSourceProperties properties = new DataSourceProperties();
                    properties.setUrl("jdbc:postgresql://127.0.0.1:1/never");
                    properties.setUsername("app");
                    properties.setPassword("app");
                    return properties;
                })
                .withBean(SimpleMeterRegistry.class)
                .withUserConfiguration(MismatchAuditDataSourceConfig.class);
    }

    @Test
    void theWorkerStartsWithoutThePool() {
        runner().withPropertyValues("memetrade.process=worker").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MismatchAuditDataSource.class);
        });
    }

    @Test
    void theApiHasThePoolAndItIsNeitherADataSourceNorATransactionManagerBean() {
        runner().withPropertyValues("memetrade.process=api").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MismatchAuditDataSource.class);
            assertThat(context).doesNotHaveBean(DataSource.class);
            assertThat(context).doesNotHaveBean(PlatformTransactionManager.class);
        });
    }
}
