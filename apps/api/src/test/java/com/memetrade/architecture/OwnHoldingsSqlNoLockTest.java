package com.memetrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5 (written before the code; NFR1.4; performance-design.md "Travas e conexões"):
 * none of the new SQL statements of the U4 (the open sales of C16, the checks P1 and P2 and the list of the dispatch)
 * takes a lock: no {@code for update}, no {@code for share}, no advisory lock.
 */
class OwnHoldingsSqlNoLockTest {

    static final Map<String, String> NEW_SQL = Map.of(
            "com.memetrade.realexecution.infra.JdbcRealExecutionStore", "OPEN_SELLS_SQL",
            "com.memetrade.bots.infra.JdbcBotRepository", "PRECHECK_SQL,SLOT_CLAIMED_SQL,ACTIVE_DISPATCH_SQL");

    @Test
    void noNewStatementTakesALock() throws Exception {
        for (Map.Entry<String, String> owner : NEW_SQL.entrySet()) {
            for (String constant : owner.getValue().split(",")) {
                String sql = constant(owner.getKey(), constant).toLowerCase(Locale.ROOT);

                assertThat(sql)
                        .as("%s.%s", owner.getKey(), constant)
                        .doesNotContain("for update")
                        .doesNotContain("for share")
                        .doesNotContain("for no key update")
                        .doesNotContain("pg_advisory");
            }
        }
    }

    static String constant(String className, String name) throws ReflectiveOperationException {
        Field field = Class.forName(className).getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
