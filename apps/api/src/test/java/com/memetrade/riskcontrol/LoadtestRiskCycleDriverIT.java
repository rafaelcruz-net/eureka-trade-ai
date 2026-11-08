package com.memetrade.riskcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.loadtest.LoadtestCycleAuthContext;
import com.memetrade.ledger.loadtest.LoadtestCycleDriver;
import com.memetrade.ledger.loadtest.LoadtestLedgerSeed.Bot;
import com.memetrade.ledger.loadtest.LoadtestTokens;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import com.memetrade.marketdata.infra.JdbcMarketListingQuery;
import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob.Round;
import com.memetrade.riskcontrol.loadtest.LoadtestRiskCycleDriver;
import com.memetrade.riskcontrol.loadtest.LoadtestRiskHealthIndicator;
import com.memetrade.riskcontrol.loadtest.LoadtestRiskPurgeTrigger;
import com.memetrade.riskcontrol.loadtest.LoadtestRiskSeed;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 16 of U6 (cicd-pipeline.md): the load cycle driver of the scenario {@code u6} on a small scale, with the
 * profiles {@code test,loadtest}. The seed writes the rejections and the old approved evaluations through the U6
 * repositories over the bots the ledger load prepared; a cycle with wide limits is approved and one with tight limits
 * refused, both timed in {@code loadtest.risk.evaluate} (window {@code before-purge}); the trigger runs the purge once
 * through its lock; the health component is UNKNOWN until the cycles run. The automatic start stays off.
 */
@Tag("u6")
@ActiveProfiles({"test", "loadtest"})
@Import(LoadtestRiskCycleDriverIT.MarketConfig.class)
class LoadtestRiskCycleDriverIT extends AbstractPostgresIT {

    private static final TestWallet SMOKE = TestWallet.generate();

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
    private LoadtestRiskCycleDriver driver;

    @Autowired
    private LoadtestRiskSeed seed;

    @Autowired
    private LoadtestRiskPurgeTrigger trigger;

    @Autowired
    private LoadtestRiskHealthIndicator health;

    @Autowired
    private ControllableMarketData market;

    @Autowired
    private ListedTokens listed;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void market() {
        market.clear();
        listed.tokens.clear();
    }

    @Test
    void theSeedWritesRejectionsAndOldApprovedEvaluationsOverThePreparedBots() throws Exception {
        List<Bot> bots = preparedBots();
        long rejectionsBefore = seed.rejectionsSeeded();

        seed.seed(bots, 3, 5, 4);

        assertThat(seed.rejectionsSeeded() - rejectionsBefore).isEqualTo(5);
        assertThat(count(
                        "select count(*) from order_rejection where bot_id = :b",
                        bots.getFirst().botId()))
                .isEqualTo(5);
        assertThat(count(
                        "select count(*) from rejection_reason_detail d join order_rejection r using (rejection_id)"
                                + " where r.bot_id = :b",
                        bots.getFirst().botId()))
                .isEqualTo(8);
        long old = 0;
        for (Bot bot : bots) {
            old += count(
                    "select count(*) from order_evaluation where bot_id = :b and outcome = 'APPROVED'"
                            + " and evaluated_at < now() - interval '31 days'",
                    bot.botId());
        }
        assertThat(old).isEqualTo(4);
    }

    @Test
    void aWideCycleIsApprovedATightOneRefusedAndBothAreTimed() throws Exception {
        Bot bot = preparedBots().getFirst();
        long timed = timed();
        int sampled = samples().size();
        long approved = driver.approved();
        long rejected = driver.rejected();

        driver.cycle(bot, 0);
        driver.cycle(bot, 1);

        assertThat(timed()).isEqualTo(timed + 2);
        // Loop 3 (F12): every timed call is also one sample, in whole microseconds, of the window it ran in.
        List<Long> after = samples();
        assertThat(after).hasSize(sampled + 2);
        assertThat(after.subList(sampled, after.size()))
                .allSatisfy(micros -> assertThat(micros).isPositive());
        assertThat(driver.evaluateMicros()).doesNotContainKey("with-purge");
        assertThat(driver.approved()).isEqualTo(approved + 1);
        assertThat(driver.rejected()).isEqualTo(rejected + 1);
        assertThat(driver.failures()).isZero();
        assertThat(count(
                        "select count(*) from order_rejection where bot_id = :b and reasons = 'ORDER_SIZE'",
                        bot.botId()))
                .isEqualTo(1);
    }

    @Test
    void aTokenWithoutAKnownPriceIsSkippedWithoutCallingTheGate() throws Exception {
        Bot bot = preparedBots().getFirst();
        market.price(bot.tokens().getFirst(), ControllableMarketData.unknown());
        long skipped = driver.skipped();
        long timed = timed();
        int sampled = samples().size();

        driver.cycle(bot, 0);

        assertThat(driver.skipped()).isEqualTo(skipped + 1);
        assertThat(timed()).isEqualTo(timed);
        assertThat(samples()).hasSize(sampled);
    }

    @Test
    void theTriggerRunsThePurgeOnceThroughItsLockAndTheHealthShowsIt() throws Exception {
        seed.seed(preparedBots(), 0, 0, 3);

        Round round = trigger.fire();

        assertThat(round.ran()).isTrue();
        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(round.deleted()).isGreaterThanOrEqualTo(3);
        Health status = health.health();
        assertThat(status.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(status.getDetails())
                .containsEntry("state", "DISABLED")
                .containsEntry("purgeResult", "SUCCEEDED")
                .containsKeys(
                        "rejectionsSeeded",
                        "oldApprovedSeeded",
                        "evaluations",
                        "cycleFailures",
                        "purgeAtSeconds",
                        "evaluateMicros")
                .doesNotContainKey("failure");
    }

    /** The samples of the window before the purge (the automatic start stays off, so cycles are never "with-purge"). */
    private List<Long> samples() {
        return driver.evaluateMicros().getOrDefault("before-purge", List.of());
    }

    private long timed() {
        return registry.find("loadtest.risk.evaluate").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count)
                .sum();
    }

    /** One person signs in and reads the ledger (so the load context remembers them), then the ledger load prepares. */
    private List<Bot> preparedBots() throws Exception {
        List<TokenId> traded = new ArrayList<>();
        TokenId token = new TokenId("solana", SyntheticIds.mint());
        market.set(token, "0.002500", "250000.000000", 6);
        listed.tokens.add(token);
        traded.addAll(tokens.tradable(1));
        UUID userId = signInAndReadTheLedger().userId();
        Principal person = loadAuth.people().stream()
                .filter(principal -> principal.userId().equals(userId))
                .findFirst()
                .orElseThrow();
        return ledger.prepare(List.of(person), traded);
    }

    private TestAccounts.Seeded signInAndReadTheLedger() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow flow = new AuthFlow(mvc, json);
        String bearer = "Bearer " + flow.accessToken(flow.login(wallet));
        int status = mvc.perform(get("/api/v1/ledger").header("Authorization", bearer))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).isEqualTo(200);
        return seeded;
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("b", id).query(Long.class).single();
    }

    /** The tokens the listing double returns, set by each test. */
    static final class ListedTokens {
        final List<TokenId> tokens = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MarketConfig {

        @Bean
        @Primary
        ControllableMarketData controllableMarketData(JdbcMarketDataQuery real, Clock clock) {
            return new ControllableMarketData(real, clock);
        }

        @Bean
        ListedTokens riskListedTokens() {
            return new ListedTokens();
        }

        /** The listing of U4 with exactly the tokens of the test (the simulated collector is off in tests). */
        @Bean
        @Primary
        MarketListingQuery riskListedTokensQuery(ListedTokens listed, JdbcMarketListingQuery real) {
            return new MarketListingQuery() {
                @Override
                public ListingResult list(ListingRequest request) {
                    List<ListedToken> page = new ArrayList<>();
                    for (TokenId token : listed.tokens) {
                        page.add(new ListedToken(
                                token,
                                "SYN",
                                "Synthetic",
                                Map.of(),
                                new ListedRisk(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()),
                                new Position(Optional.empty(), token.mint(), token.chain())));
                    }
                    return new ListingResult(page, false, page.size(), 0, 0);
                }

                @Override
                public List<com.memetrade.marketdata.api.SourceStatus> sources() {
                    return real.sources();
                }
            };
        }
    }
}
