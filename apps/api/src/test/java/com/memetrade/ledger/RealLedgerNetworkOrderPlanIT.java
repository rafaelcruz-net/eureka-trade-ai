package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.ledger.infra.JdbcRealLedger;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.OwnTestDatabase;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 261121-paper-real-unico U2, Etapa 6 (written before the code; NFR1.15, NFR1.18, performance-design.md "Conferência do
 * plano"): on a database of its own with two people at the reference density of NFR1.14 (5 REAL bots, 4 orders per bot
 * per day, 2 or 3 entries per order; one and two years), after ANALYZE, the plan of the EXACT SQL of the read in
 * network order (with {@code work_mem} 12 MB) and of the view (default {@code work_mem}) uses
 * {@code real_ledger_entry_wallet_created_idx}, has no Seq Scan on {@code real_ledger_entry} and never sorts on disk.
 * The read is fetched 500 rows at a time.
 */
@Tag("u2-real-bot-pnl")
class RealLedgerNetworkOrderPlanIT {

    private static final String PREFIX = "u2plan_";
    private static final String INDEX = "real_ledger_entry_wallet_created_idx";
    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private static UUID oneYearTenant;
    private static UUID oneYearWallet;
    private static UUID twoYearsTenant;
    private static UUID twoYearsWallet;

    @BeforeAll
    static void seed() {
        JdbcClient app = OwnTestDatabase.connect(PREFIX, AbstractPostgresIT.APP);
        UUID[] first = person(app, 365);
        UUID[] second = person(app, 730);
        oneYearTenant = first[0];
        oneYearWallet = first[1];
        twoYearsTenant = second[0];
        twoYearsWallet = second[1];
        // 261121-paper-real-unico U2a, Etapa 14a (decision of the founder): 20 other people at the reference volume, so
        // the wallet measured is a small part of the table, as in production. The criterion does not change.
        for (int other = 0; other < 20; other++) {
            person(app, 730);
        }
        // Etapa 14b: the marks of zero the writers keep, one per mint of the measured wallet, at its last fill.
        marks(app, twoYearsTenant, twoYearsWallet);
        OwnTestDatabase.analyze(PREFIX);
    }

    /**
     * 261121-paper-real-unico U2a, Etapa 14b (decision of the founder of 2026-11-22): the view restricted by the marks
     * of zero reads the whole history only to aggregate it (balance, count, flows and the fills of each mint) and sorts
     * only the rows after the marks, so it runs with the DEFAULT {@code work_mem} (none is set) and still uses the index
     * of the migration, never a Seq Scan on {@code real_ledger_entry} and never a sort on disk. The full view (the
     * fallback when a mark disagrees) keeps its own {@code work_mem} (the test above).
     */
    @Test
    void theViewRestrictedByTheMarksUsesTheIndexAndSortsInMemoryWithTheDefaultWorkMem() throws Exception {
        for (Timestamp flows : java.util.Arrays.asList(null, Timestamp.from(NOW.minusSeconds(86_400)))) {
            JsonNode plan = explain(
                    JdbcRealLedger.VIEW_EPOCH_SQL,
                    new MapSqlParameterSource()
                            .addValue("tenant", twoYearsTenant)
                            .addValue("wallet", twoYearsWallet)
                            .addValue("since", null, java.sql.Types.TIMESTAMP)
                            .addValue("flows", flows, java.sql.Types.TIMESTAMP),
                    null);

            assertUsesTheIndexWithoutSeqScanNorDiskSort(plan);
        }
    }

    @Test
    void theReadInNetworkOrderUsesTheIndexOfTheMigrationAndSortsInMemory() throws Exception {
        for (UUID[] person :
                List.of(new UUID[] {oneYearTenant, oneYearWallet}, new UUID[] {twoYearsTenant, twoYearsWallet})) {
            JsonNode plan = explain(
                    JdbcRealLedger.NETWORK_ORDER_SQL,
                    new MapSqlParameterSource()
                            .addValue("tenant", person[0])
                            .addValue("wallets", List.of(person[1]))
                            .addValue(
                                    "kinds",
                                    Arrays.stream(com.memetrade.ledger.api.RealEntryKind.values())
                                            .map(Enum::name)
                                            .toList()),
                    "12MB");

            assertUsesTheIndexWithoutSeqScanNorDiskSort(plan);
        }
    }

    @Test
    void theViewUsesTheIndexOfTheMigrationAndSortsInMemoryWithItsWorkMem() throws Exception {
        for (String sql : List.of(JdbcRealLedger.VIEW_SQL, JdbcRealLedger.VIEW_WITH_FLOWS_SQL)) {
            // Etapa 14a: the view sorts the whole history of the wallet (the inheritance of BR1.3 needs every row), so
            // it runs with its own local work_mem (JdbcRealLedger.VIEW_WORK_MEM), restored right after the statement.
            JsonNode plan = explain(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("tenant", twoYearsTenant)
                            .addValue("wallet", twoYearsWallet)
                            .addValue("since", Timestamp.from(NOW.minusSeconds(86_400))),
                    JdbcRealLedger.VIEW_WORK_MEM);

            assertUsesTheIndexWithoutSeqScanNorDiskSort(plan);
        }
    }

    @Test
    void theReadIsFetched500RowsAtATime() {
        assertThat(JdbcRealLedger.STREAM_FETCH_SIZE).isEqualTo(500);
    }

    // ----- helpers ---------------------------------------------------------------------------------------------------

    private static void assertUsesTheIndexWithoutSeqScanNorDiskSort(JsonNode plan) {
        List<JsonNode> nodes = new ArrayList<>();
        walk(plan, nodes::add);
        assertThat(nodes)
                .as("an index node on real_ledger_entry named %s in %s", INDEX, plan)
                .anySatisfy(node -> {
                    assertThat(node.path("Index Name").asText()).isEqualTo(INDEX);
                    // An Index Scan names its relation; a Bitmap Index Scan only its index (the index is of
                    // real_ledger_entry by its name), as pnl-plans.mjs of performance-design.md accepts.
                    if (node.has("Relation Name")) {
                        assertThat(node.path("Relation Name").asText()).isEqualTo("real_ledger_entry");
                    }
                });
        assertThat(nodes).as("no Seq Scan on real_ledger_entry in %s", plan).noneSatisfy(node -> {
            assertThat(node.path("Node Type").asText()).isEqualTo("Seq Scan");
            assertThat(node.path("Relation Name").asText()).isEqualTo("real_ledger_entry");
        });
        assertThat(nodes)
                .extracting(node -> node.path("Sort Method").asText())
                .as("no sort on disk in %s", plan)
                .doesNotContain("external merge", "external sort");
    }

    private static void walk(JsonNode node, Consumer<JsonNode> visitor) {
        if (node.isObject() && node.has("Node Type")) {
            visitor.accept(node);
        }
        node.forEach(child -> walk(child, visitor));
    }

    private static JsonNode explain(String sql, MapSqlParameterSource params, String workMem) throws Exception {
        String url = OwnTestDatabase.url(PREFIX);
        try (Connection connection =
                DriverManager.getConnection(url, AbstractPostgresIT.APP.user(), AbstractPostgresIT.APP.password())) {
            connection.setAutoCommit(false);
            try {
                NamedParameterJdbcTemplate named =
                        new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
                if (workMem != null) {
                    named.getJdbcTemplate().execute("set local work_mem = '" + workMem + "'");
                }
                String json = named.queryForObject("explain (analyze, format json) " + sql, params, String.class);
                return new ObjectMapper().readTree(json);
            } finally {
                connection.rollback();
            }
        }
    }

    /** One mark of zero per mint of the wallet, at its last fill in recording order (the shape of the plan only). */
    private static void marks(JdbcClient app, UUID tenant, UUID wallet) {
        app.sql("""
                        insert into real_position_epoch (tenant_id, bot_wallet_id, mint, zero_slot, zero_inherited,
                          zero_tx_signature, zero_created_at, zero_reference, zero_kind_rank, zero_idempotency_key,
                          fills_through, window_from, reason, recorded_at)
                        select distinct on (e.mint) e.tenant_id, e.bot_wallet_id, e.mint, coalesce(s.slot, 0),
                          s.slot is null, e.tx_signature, e.created_at, e.reference, 0, e.idempotency_key, 0,
                          e.created_at, 'ZERO', e.created_at
                        from real_ledger_entry e
                        left join real_entry_slot s on s.tx_signature = e.tx_signature
                        where e.tenant_id = :tenant and e.bot_wallet_id = :wallet
                          and e.entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT')
                        order by e.mint, e.created_at desc""").param("tenant", tenant).param("wallet", wallet).update();
    }

    /** One person with one bot wallet, 5 REAL bots, 4 orders per bot per day for {@code days} days. */
    private static UUID[] person(JdbcClient app, int days) {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(app, TestWallet.generate().address());
        UUID wallet = UUID.randomUUID();
        app.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", wallet)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.walletAddress())
                .param("at", Timestamp.from(NOW.minusSeconds(86_400L * (days + 1))))
                .update();
        Map<String, Object> params = Map.of(
                "tenant",
                person.tenantId(),
                "wallet",
                wallet,
                "orders",
                days * 20,
                "start",
                Timestamp.from(NOW.minusSeconds(86_400L * days)));
        // Order n: a buy (fill, fee, rent) when n is even, a sale (fill, fee) when odd; one every 72 minutes.
        app.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                          tx_signature, reference, idempotency_key, created_at, mint, token_base_units)
                        select gen_random_uuid(), :tenant, :wallet, k.kind,
                          case k.kind when 'SWAP_FILL_IN' then -50000000 when 'SWAP_FILL_OUT' then 55000000
                            when 'NETWORK_FEE' then -5000 else -2039280 end,
                          'plan-' || :wallet || '-' || n, 'order:' || :wallet || '-' || n,
                          :wallet || ':' || n || ':' || k.kind,
                          cast(:start as timestamptz) + n * interval '72 minutes',
                          case when k.kind like 'SWAP_FILL_%' then 'MintPlan' || (n % 10) end,
                          case when k.kind like 'SWAP_FILL_%' then 1000 end
                        from generate_series(1, :orders) n
                        cross join lateral (values
                          (case when n % 2 = 0 then 'SWAP_FILL_IN' else 'SWAP_FILL_OUT' end),
                          ('NETWORK_FEE'),
                          (case when n % 2 = 0 then 'RENT' end)) k(kind)
                        where k.kind is not null""").params(params).update();
        // The second half of the history has its slot recorded (after the migration 0023); the first half does not.
        app.sql("""
                        insert into real_entry_slot (tx_signature, slot, recorded_at)
                        select 'plan-' || :wallet || '-' || n, 1000000 + n * 150, now()
                        from generate_series(:orders / 2, :orders) n""").params(params).update();
        return new UUID[] {person.tenantId(), wallet};
    }
}
