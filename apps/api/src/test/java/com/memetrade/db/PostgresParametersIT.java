package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * NFR7.1 and NFR4.2: the test database runs with the U2 server parameters (the same list docker-compose.yml passes);
 * the test fails when any of them diverges.
 */
@Tag("u2")
class PostgresParametersIT extends AbstractPostgresIT {

    @Test
    void commitTimestampsAreTrackedForTheDeliveryMeasure() {
        assertThat(show("track_commit_timestamp")).isEqualTo("on");
    }

    @Test
    void statementsParametersAndFailingRowsAreNeverLogged() {
        assertThat(show("log_statement")).isEqualTo("none");
        assertThat(show("log_min_duration_statement")).isEqualTo("-1");
        assertThat(show("log_min_error_statement")).isEqualTo("panic");
        assertThat(show("log_error_verbosity")).isEqualTo("terse");
        assertThat(show("log_parameter_max_length")).isEqualTo("0");
        assertThat(show("log_parameter_max_length_on_error")).isEqualTo("0");
    }

    @Test
    void everyDeclaredParameterIsActiveInTheServer() {
        Map<String, String> expected = new LinkedHashMap<>();
        SERVER_PARAMETERS.forEach(p -> expected.put(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1)));

        expected.forEach((name, value) -> assertThat(show(name)).as(name).isEqualTo(value));
    }

    private String show(String parameter) {
        return connect(SUPERUSER)
                .sql("select setting from pg_settings where name = :n")
                .param("n", parameter)
                .query(String.class)
                .single();
    }
}
