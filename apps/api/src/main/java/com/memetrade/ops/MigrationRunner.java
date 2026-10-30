package com.memetrade.ops;

import java.util.TimeZone;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of the {@code db-migrate} service (security-design.md, "Migrações fora da API"): the same artifact as
 * the API, started with the {@code migrate} profile, applies the Liquibase changelog with the schema OWNER login and
 * exits. It loads only the DataSource and Liquibase auto-configuration: no web server, no scheduler, no API bean.
 * The API itself runs with Liquibase disabled and never receives the owner credential.
 *
 * <p>Run inside the image with
 * {@code java -Dloader.main=com.memetrade.ops.MigrationRunner -cp /app/app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}. A failed changeset ends with a non-zero exit code.
 *
 * <p>Not a {@code @Configuration}: component scanning of the API never picks it up.
 */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, LiquibaseAutoConfiguration.class})
public class MigrationRunner {

    public static final String PROFILE = "migrate";

    public static void main(String[] args) {
        System.exit(migrate(args));
    }

    /** Applies the changelog and returns the process exit code (0 when every changeset is applied). */
    public static int migrate(String... args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(MigrationRunner.class)
                .profiles(PROFILE)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
        return SpringApplication.exit(context);
    }
}
