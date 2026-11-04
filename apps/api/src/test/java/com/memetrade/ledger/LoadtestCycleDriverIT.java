package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.loadtest.LoadtestCycleAuthContext;
import com.memetrade.ledger.loadtest.LoadtestCycleDriver;
import com.memetrade.ledger.loadtest.LoadtestLedgerHealthIndicator;
import com.memetrade.ledger.loadtest.LoadtestLedgerSeed.Bot;
import com.memetrade.ledger.loadtest.LoadtestTokens;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import com.memetrade.marketdata.infra.JdbcMarketListingQuery;
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
 * Passo 23 (cicd-pipeline.md): the load cycle driver of the {@code loadtest} profile, on a small scale. The people come
 * only from validated sessions (no principal is built), the preparation funds every bot once, opens one position per
 * token and seeds the fills through the real {@code PaperExecution}, a cycle is timed in
 * {@code loadtest.paper.execute}, and a token without a known price is never traded. The automatic start stays off
 * ({@code people} 0); the test drives the preparation and the cycles directly.
 */
@Tag("u7")
@ActiveProfiles({"test", "loadtest"})
@Import(LoadtestCycleDriverIT.MarketConfig.class)
class LoadtestCycleDriverIT extends AbstractPostgresIT {

    private static final TestWallet SMOKE = TestWallet.generate();

    @DynamicPropertySource
    static void loadSize(DynamicPropertyRegistry registry) {
        registry.add("memetrade.smoke.beta-wallet-address", SMOKE::address);
        registry.add("memetrade.loadtest.ledger.people", () -> "0");
        registry.add("memetrade.loadtest.ledger.cycle-bots", () -> "4");
        registry.add("memetrade.loadtest.ledger.fills-per-person", () -> "20");
        registry.add("memetrade.loadtest.ledger.tokens-per-bot", () -> "2");
        registry.add("memetrade.loadtest.ledger.threads", () -> "2");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AuthContext auth;

    @Autowired
    private LoadtestCycleAuthContext loadAuth;

    @Autowired
    private LoadtestCycleDriver driver;

    @Autowired
    private LoadtestTokens tokens;

    @Autowired
    private LoadtestLedgerHealthIndicator health;

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
    void theAuthContextOfTheLoadIsTheRequestOneAndRemembersOnlySessionPeople() throws Exception {
        assertThat(auth).isSameAs(loadAuth);
        assertThat(auth.optional()).isEmpty();

        TestAccounts.Seeded person = signInAndReadTheLedger();

        Principal seen = rememberedPerson(person.userId());
        assertThat(seen.tenantId()).isEqualTo(person.tenantId());
        assertThat(loadAuth.as(seen, auth::current)).isEqualTo(seen);
        assertThat(auth.optional()).isEmpty();
    }

    @Test
    void thePreparationFundsOpensOnePositionPerTokenAndSeedsTheFillsOfEveryPerson() throws Exception {
        List<TokenId> traded = tradableTokens();
        List<Principal> people = List.of(
                rememberedPerson(signInAndReadTheLedger().userId()),
                rememberedPerson(signInAndReadTheLedger().userId()));

        List<Bot> bots = driver.prepare(people, traded);

        assertThat(bots).hasSize(4);
        for (Principal person : people) {
            assertThat(count("select count(*) from fill where owner_user_id = :p and mode = 'PAPER'", person))
                    .isGreaterThanOrEqualTo(20);
            assertThat(count("select count(*) from ledger_entry where owner_user_id = :p and type = 'FUNDING'", person))
                    .isEqualTo(2);
            assertThat(count("select count(*) from position where owner_user_id = :p and status = 'OPEN'", person))
                    .isEqualTo(4);
        }
    }

    @Test
    void aCycleBuysThenSellsAndEveryCallIsTimed() throws Exception {
        List<TokenId> traded = tradableTokens();
        Principal person = rememberedPerson(signInAndReadTheLedger().userId());
        Bot bot = driver.prepare(List.of(person), traded).get(0);
        long before = count("select count(*) from fill where bot_id = :p", bot.botId());
        long timed = registry.get("loadtest.paper.execute").timer().count();

        driver.cycle(bot);
        driver.cycle(bot);

        assertThat(registry.get("loadtest.paper.execute").timer().count()).isEqualTo(timed + 2);
        assertThat(count("select count(*) from fill where bot_id = :p", bot.botId()))
                .isEqualTo(before + 2);
        assertThat(driver.cycleFailures()).isZero();
    }

    /** Review repair R-04: a bot closed by the driver runs F3 in a cycle transaction, timed in loadtest.paper.close. */
    @Test
    void closingABotRunsClosePositionsAndIsTimed() throws Exception {
        List<TokenId> traded = tradableTokens();
        Principal person = rememberedPerson(signInAndReadTheLedger().userId());
        Bot bot = driver.prepare(List.of(person), traded).get(0);
        long timed = registry.get("loadtest.paper.close").timer().count();
        long closes = driver.closes();

        driver.closeBot(bot);

        assertThat(registry.get("loadtest.paper.close").timer().count()).isEqualTo(timed + 1);
        assertThat(driver.closes()).isEqualTo(closes + 1);
        assertThat(driver.closeFailures()).isZero();
        assertThat(jdbc.sql("select status from paper_account where bot_id = :b")
                        .param("b", bot.botId())
                        .query(String.class)
                        .single())
                .isEqualTo("CLOSED");
        assertThat(count("select count(*) from position where bot_id = :p and quantity > 0", bot.botId()))
                .isZero();
        assertThat(health.health().getDetails()).containsKeys("closeBots", "closes", "closeFailures");
    }

    @Test
    void aTokenWithAnUnknownPriceIsNeverTradedAndNeverValuedAtZero() {
        TokenId unknown = new TokenId("solana", SyntheticIds.mint());
        listed.tokens.add(unknown);
        market.set(unknown, "0.500000", "500000.000000", 6);
        market.price(unknown, ControllableMarketData.unknown());

        assertThat(tokens.quantity(unknown)).isEmpty();
        assertThat(tokens.tradable(5)).doesNotContain(unknown);
    }

    @Test
    void theHealthComponentIsUnknownWithNumbersUntilTheCyclesRun() {
        Health status = health.health();

        assertThat(status.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(status.getDetails())
                .containsEntry("state", "DISABLED")
                .containsEntry("peopleExpected", 0)
                .containsKeys("peopleSeen", "fillsRecorded", "cycles", "cycleFailures");
        assertThat(status.getDetails()).doesNotContainKey("failure");
    }

    private List<TokenId> tradableTokens() {
        for (int i = 0; i < 3; i++) {
            TokenId token = new TokenId("solana", SyntheticIds.mint());
            market.set(token, "0.002500", "250000.000000", 6);
            listed.tokens.add(token);
        }
        List<TokenId> traded = tokens.tradable(2);
        assertThat(traded).hasSize(2);
        assertThat(tokens.quantity(traded.get(0))).contains(new java.math.BigDecimal("4000.000000"));
        return traded;
    }

    /** A BETA_USER person signs in and reads a C5 route, as the load script does before the preparation. */
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

    private Principal rememberedPerson(UUID userId) {
        return loadAuth.people().stream()
                .filter(principal -> principal.userId().equals(userId))
                .findFirst()
                .orElseThrow();
    }

    private long count(String sql, Principal person) {
        return count(sql, person.userId());
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("p", id).query(Long.class).single();
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
        ListedTokens listedTokens() {
            return new ListedTokens();
        }

        /** The listing of U4 with exactly the tokens of the test (the simulated collector is off in tests). */
        @Bean
        @Primary
        MarketListingQuery listedTokensQuery(ListedTokens listed, JdbcMarketListingQuery real) {
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
