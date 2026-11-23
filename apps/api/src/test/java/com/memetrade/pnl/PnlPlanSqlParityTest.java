package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.infra.JdbcRealLedger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 25 (NFR1.18, performance-design.md "Conferência do plano"): the query the load
 * scenario {@code pnl-by-mode} sends to EXPLAIN ({@code scripts/load/pnl-plans.mjs}) is exactly the main REAL read of
 * the application, {@link JdbcRealLedger#NETWORK_ORDER_SQL}; a change of one without the other fails here.
 */
class PnlPlanSqlParityTest {

    private static final Path SCRIPT = Path.of("..", "..", "scripts", "load", "pnl-plans.mjs");
    private static final Pattern CONSTANT =
            Pattern.compile("export const NETWORK_ORDER_SQL = `(.*?)`;", Pattern.DOTALL);

    @Test
    void theQueryOfThePlanCheckIsTheQueryOfTheApplication() throws IOException {
        Matcher constant = CONSTANT.matcher(Files.readString(SCRIPT, StandardCharsets.UTF_8));

        assertThat(constant.find())
                .as("NETWORK_ORDER_SQL in scripts/load/pnl-plans.mjs")
                .isTrue();
        assertThat(normalize(constant.group(1))).isEqualTo(normalize(JdbcRealLedger.NETWORK_ORDER_SQL));
    }

    @Test
    void theApplicationQueryFiltersByTenantAndWalletAndNeverOrdersByTheEntryId() {
        String sql = JdbcRealLedger.NETWORK_ORDER_SQL;
        assertThat(sql)
                .contains("e.tenant_id = :tenant")
                .contains("e.bot_wallet_id in (:wallets)")
                .contains("collate \"C\"");
        assertThat(sql.substring(sql.lastIndexOf("order by"))).doesNotContain("entry_id");
    }

    private static String normalize(String sql) {
        return sql.replace("\r\n", "\n").strip();
    }
}
