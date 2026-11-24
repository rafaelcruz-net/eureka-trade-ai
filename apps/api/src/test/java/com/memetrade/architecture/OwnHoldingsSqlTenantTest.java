package com.memetrade.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5 (written before the code; NFR3.1; project.md "ALWAYS validar o tenant no servidor
 * em toda leitura"): the new statements of the read filter by the tenant: both parts of the {@code UNION ALL} of the
 * open sales, and the check P1 that reads the mark of the bot. The states, the side and PENDING are literals, never
 * parameters, so the planner proves the partial indexes (performance-design.md).
 */
class OwnHoldingsSqlTenantTest {

    @Test
    void bothPartsOfTheOpenSalesFilterByTheTenantWithLiterals() throws Exception {
        String sql = OwnHoldingsSqlNoLockTest.constant(
                "com.memetrade.realexecution.infra.JdbcRealExecutionStore", "OPEN_SELLS_SQL");
        String[] parts = sql.toLowerCase(java.util.Locale.ROOT).split("union all");

        assertThat(parts).hasSize(2);
        assertThat(parts[0])
                .contains("tenant_id = :tenant")
                .contains("bot_id = :bot")
                .contains("side = 'sell'")
                .contains("state in ('proposed', 'quoted', 'signed', 'sent', 'ambiguous')");
        assertThat(parts[1])
                .contains("tenant_id = :tenant")
                .contains("bot_id = :bot")
                .contains("side = 'sell'")
                .contains("state = 'pending'")
                .doesNotContain("not_before");
    }

    @Test
    void theCheckP1FiltersByBotTenantAndOwner() throws Exception {
        String sql = OwnHoldingsSqlNoLockTest.constant("com.memetrade.bots.infra.JdbcBotRepository", "PRECHECK_SQL");

        assertThat(sql)
                .contains("tenant_id = :tenant")
                .contains("owner_user_id = :owner")
                .contains("bot_id = :bot")
                .contains("change_seq");
    }
}
