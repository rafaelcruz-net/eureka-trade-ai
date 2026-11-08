package com.memetrade.riskcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.loadtest.LoadtestCycleAuthContext;
import com.memetrade.ledger.loadtest.LoadtestCycleDriver;
import com.memetrade.ledger.loadtest.LoadtestLedgerSeed.Bot;
import com.memetrade.ledger.loadtest.LoadtestTokens;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.loadtest.LoadtestRiskSeed;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 23 of U6 (volta 2 of Build and Test, F10, NFR2.5), WRITTEN BEFORE THE CHANGE: the seed of the scenario
 * {@code u6} returns only when the planner statistics of {@code order_evaluation} and {@code order_rejection} cover what
 * it wrote, so the purge that follows is planned on fresh statistics (index scan on the partial purge index and an
 * anti join on the index of {@code order_rejection.evaluation_id}) and never on the empty estimates of a bulk write
 * (hash anti join over a Seq Scan on {@code order_rejection}, about 570 ms per batch in Passo 22).
 *
 * <p>The application login cannot run ANALYZE on these tables (PostgreSQL 17 needs ownership or MAINTAIN; Passo 22
 * saw "permission denied to analyze ..., skipping it"), so the seed waits for autovacuum: after the seed, each table
 * was analyzed after the seed began and has at most the modifications that would make autovacuum analyze it again.
 */
@Tag("u6")
@ActiveProfiles({"test", "loadtest"})
@Import(LoadtestRiskCycleDriverIT.MarketConfig.class)
class LoadtestRiskSeedIT extends AbstractPostgresIT {

    private static final TestWallet SMOKE = TestWallet.generate();

    private static final List<String> SEEDED_TABLES = List.of("order_evaluation", "order_rejection");

    /** Rows written by the test seed: well above the autovacuum analyze threshold of either table in the test base. */
    private static final long REJECTIONS = 2_000;

    private static final long OLD_APPROVED = 6_000;

    @DynamicPropertySource
    static void loadSize(DynamicPropertyRegistry registry) {
        registry.add("memetrade.smoke.beta-wallet-address", SMOKE::address);
        registry.add("memetrade.loadtest.ledger.people", () -> "0");
        registry.add("memetrade.loadtest.ledger.cycle-bots", () -> "2");
        registry.add("memetrade.loadtest.ledger.fills-per-person", () -> "0");
        registry.add("memetrade.loadtest.ledger.tokens-per-bot", () -> "1");
        registry.add("memetrade.loadtest.ledger.threads", () -> "2");
        registry.add("memetrade.loadtest.risk.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private LoadtestCycleAuthContext loadAuth;

    @Autowired
    private LoadtestCycleDriver ledger;

    @Autowired
    private LoadtestTokens tokens;

    @Autowired
    private LoadtestRiskSeed seed;

    @Autowired
    private ControllableMarketData market;

    @Autowired
    private LoadtestRiskCycleDriverIT.ListedTokens listed;

    @Test
    void afterTheSeedBothSeededTablesAreAnalyzedAndCarryNoModificationAutovacuumWouldStillAnalyze() throws Exception {
        List<Bot> bots = preparedBots();
        JdbcClient owner = connect(OWNER);
        // The volume must be big enough for autovacuum to analyze each table, or the test proves nothing: every
        // rejection writes one evaluation and one rejection, every old approved one evaluation.
        assertThat(REJECTIONS + OLD_APPROVED).isGreaterThan(threshold(owner, "order_evaluation"));
        assertThat(REJECTIONS).isGreaterThan(threshold(owner, "order_rejection"));
        Timestamp seedStarted = owner.sql("select now()").query(Timestamp.class).single();

        seed.seed(bots, 0, REJECTIONS, OLD_APPROVED);

        for (String table : SEEDED_TABLES) {
            Map<String, Object> stats = stats(owner, table);
            assertThat((Timestamp) stats.get("analyzed"))
                    .as("last analyze of %s after the seed began", table)
                    .isNotNull()
                    .isAfter(seedStarted);
            assertThat((Long) stats.get("modified"))
                    .as("modifications of %s since the last analyze", table)
                    .isLessThanOrEqualTo(threshold(owner, table));
        }
    }

    @Test
    void aSeedBelowTheAutovacuumThresholdReturnsWithoutWaiting() throws Exception {
        List<Bot> bots = preparedBots();
        long started = System.nanoTime();

        seed.seed(bots, 0, 1, 2);

        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(30);
    }

    /** The modifications above which autovacuum analyzes {@code table} again (server settings, current reltuples). */
    private static long threshold(JdbcClient owner, String table) {
        return owner.sql("""
                        select floor(current_setting('autovacuum_analyze_threshold')::numeric
                            + current_setting('autovacuum_analyze_scale_factor')::numeric
                              * greatest(c.reltuples, 0)::numeric)::bigint
                        from pg_class c where c.relname = :table
                        """).param("table", table).query(Long.class).single();
    }

    private static Map<String, Object> stats(JdbcClient owner, String table) {
        return owner.sql("""
                        select greatest(last_analyze, last_autoanalyze) as analyzed, n_mod_since_analyze as modified
                        from pg_stat_user_tables where relname = :table
                        """).param("table", table).query().singleRow();
    }

    /** One person signs in and reads the ledger (so the load context remembers them), then the ledger load prepares. */
    private List<Bot> preparedBots() throws Exception {
        List<TokenId> traded = new ArrayList<>();
        TokenId token = new TokenId("solana", SyntheticIds.mint());
        market.set(token, "0.002500", "250000.000000", 6);
        listed.tokens.add(token);
        traded.addAll(tokens.tradable(1));
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow flow = new AuthFlow(mvc, json);
        String bearer = "Bearer " + flow.accessToken(flow.login(wallet));
        int status = mvc.perform(get("/api/v1/ledger").header("Authorization", bearer))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).isEqualTo(200);
        UUID userId = seeded.userId();
        Principal person = loadAuth.people().stream()
                .filter(principal -> principal.userId().equals(userId))
                .findFirst()
                .orElseThrow();
        return ledger.prepare(List.of(person), traded);
    }
}
