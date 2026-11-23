package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2 (infrastructure-specification.md "Permissões", NFR3.5), in the pattern of
 * {@link DatabaseRolesIT}: the privilege matrix of the tables of the U2 is exactly the one of the specification for the
 * application login, the retention group and PUBLIC, whatever the name of the application login (it comes from .env).
 */
class U2DatabaseRolesIT extends AbstractPostgresIT {

    /** Table, then the privileges of the application login, then those of the retention group. */
    private static final Map<String, List<Set<String>>> MATRIX = Map.of(
            "real_entry_slot",
            List.of(Set.of("SELECT", "INSERT"), Set.of()),
            // 0024 (F-4): risk_retention reads and deletes the marks for the purge, like real_risk_day.
            "real_risk_day_deferred",
            List.of(Set.of("SELECT", "INSERT"), Set.of("SELECT", "DELETE")),
            // 0025 (Etapa 14b): the marks of zero are only inserted, like the slots; nothing for the retention group.
            "real_position_epoch",
            List.of(Set.of("SELECT", "INSERT"), Set.of()));

    private static final List<String> ALL = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES");

    @Test
    void theApplicationHasExactlyTheMatrix() {
        MATRIX.forEach((table, expected) ->
                assertThat(granted(APP.user(), table)).as(table).isEqualTo(new TreeSet<>(expected.get(0))));
    }

    @Test
    void theRetentionGroupHasExactlyTheMatrix() {
        MATRIX.forEach((table, expected) ->
                assertThat(granted("risk_retention", table)).as(table).isEqualTo(new TreeSet<>(expected.get(1))));
    }

    @Test
    void publicHasNothingAndTheOwnerHasNoTruncate() {
        MATRIX.keySet().forEach(table -> {
            assertThat(granted("public", table)).as(table).isEmpty();
            assertThat(granted(OWNER.user(), table)).as(table).doesNotContain("TRUNCATE");
        });
    }

    private static Set<String> granted(String role, String table) {
        Set<String> granted = new TreeSet<>();
        for (String privilege : ALL) {
            boolean has = connect(OWNER)
                    .sql("select has_table_privilege(:r, :t, :p)")
                    .param("r", role)
                    .param("t", table)
                    .param("p", privilege)
                    .query(Boolean.class)
                    .single();
            if (has) {
                granted.add(privilege);
            }
        }
        return granted;
    }
}
