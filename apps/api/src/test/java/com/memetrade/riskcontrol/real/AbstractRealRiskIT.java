package com.memetrade.riskcontrol.real;

import com.memetrade.botwallet.AbstractRealIT;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Base of the integration tests of the REAL barrier (U3): the REAL switched on (profile {@code local}, as the U1 tests),
 * real PostgreSQL with the migrations of the application, and two controllable reads that the scenarios need:
 *
 * <ul>
 *   <li>M4 reads the stored market observations through the real {@link JdbcMarketDataQuery}; only the kind of the
 *       active source is switched by the test (REAL by default, SIMULATED for S11);
 *   <li>the indicator of the scanner is Available, except for the mints a test marks as unassessed (S8).
 * </ul>
 *
 * Every test creates its own people, wallets, bots, decisions and synthetic mints (never real ones); nothing is shared.
 */
@TestPropertySource(properties = "memetrade.real.enabled=true")
@ActiveProfiles("local")
@Import(AbstractRealRiskIT.RealRiskTestConfig.class)
public abstract class AbstractRealRiskIT extends AbstractRealIT {

    protected static final long SOL = 1_000_000_000L;

    /** The wrapped SOL mint: the quote that turns token quotes in dollars into lamports (LamportPrice). */
    protected static final String WSOL = "So11111111111111111111111111111111111111112";

    @Autowired
    protected RealLedger ledger;

    @Autowired
    protected ControllableRealMarket market;

    @Autowired
    protected ControllableAssessment assessment;

    @BeforeEach
    void resetDoubles() {
        market.kind.set(SourceKind.REAL);
        assessment.unassessed.clear();
    }

    /** A person, its ACTIVE bot wallet and its REAL preferences, released 25 hours ago (in force). */
    public record Wallet(UUID tenantId, UUID userId, UUID botWalletId, UUID botId) {}

    protected Wallet releasedWallet(long capLamports) {
        TestAccounts.Seeded person =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        UUID botWalletId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.walletAddress())
                .param("at", Timestamp.from(now.minus(Duration.ofDays(2))))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, 'AUTO_FULL', :cap, 30000000, 100000, :at)""")
                .param("id", botWalletId)
                .param("cap", capLamports)
                .param("at", Timestamp.from(now.minus(Duration.ofDays(2))))
                .update();
        release(person.userId());
        return new Wallet(person.tenantId(), person.userId(), botWalletId, UUID.randomUUID());
    }

    protected void deposit(Wallet wallet, long lamports) {
        String key = "dep:" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                wallet.tenantId(), wallet.botWalletId(), RealEntryKind.DEPOSIT, lamports, null, key, key)));
    }

    /** An open position bought for {@code costLamports}: a swap fill IN of the token. */
    protected void position(Wallet wallet, String mint, long units, long costLamports) {
        String order = "order:" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> ledger.record(RealEntry.swapFill(
                wallet.tenantId(),
                wallet.botWalletId(),
                RealEntryKind.SWAP_FILL_IN,
                -costLamports,
                mint,
                units,
                order,
                order + ":fill")));
    }

    /** The RESERVE of an order of the U4 still without a fill (ambiguous or in flight). */
    protected void orderReserve(Wallet wallet, long lamports) {
        String order = "order:" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                wallet.tenantId(),
                wallet.botWalletId(),
                RealEntryKind.RESERVE,
                lamports,
                null,
                order,
                order + ":reserve")));
    }

    /**
     * A token observed by a real source {@code secondsAgo} before the clock: price in dollars, liquidity and volume,
     * with its decimals. Returns the mint.
     */
    protected String token(String priceUsd, int decimals, long secondsAgo) {
        String mint = SyntheticIds.mint();
        observe(mint, priceUsd, decimals, secondsAgo);
        return mint;
    }

    /** Quotes the wrapped SOL at {@code priceUsd} (the token row is created once and refreshed). */
    protected void solAt(String priceUsd, long secondsAgo) {
        jdbc.sql("delete from market_observation where chain = 'solana' and mint = :mint")
                .param("mint", WSOL)
                .update();
        observe(WSOL, priceUsd, 9, secondsAgo);
    }

    protected void observe(String mint, String priceUsd, int decimals, long secondsAgo) {
        String source = realSource();
        jdbc.sql("""
                        insert into token (chain, mint, symbol, decimals, first_seen_at)
                        values ('solana', :mint, 'TST', :decimals, :at) on conflict do nothing""")
                .param("mint", mint)
                .param("decimals", decimals)
                .param("at", Timestamp.from(clock.instant().minus(Duration.ofDays(1))))
                .update();
        Instant at = clock.instant().minusSeconds(secondsAgo);
        for (Map.Entry<String, String> field : Map.of("PRICE", priceUsd, "LIQUIDITY", "500000", "VOLUME_24H", "250000")
                .entrySet()) {
            jdbc.sql("""
                            insert into market_observation (observation_id, chain, mint, source_id, field, status,
                              value, observed_at)
                            values (:id, 'solana', :mint, :source, :field, 'KNOWN', :value, :at)
                            on conflict (chain, mint, source_id, field)
                            do update set value = excluded.value, observed_at = excluded.observed_at""")
                    .param("id", UUID.randomUUID())
                    .param("mint", mint)
                    .param("source", source)
                    .param("field", field.getKey())
                    .param("value", new BigDecimal(field.getValue()))
                    .param("at", Timestamp.from(at))
                    .update();
        }
    }

    private String realSource() {
        jdbc.sql("insert into data_source (source_id, name, simulated, status) values ('u3-real', 'U3 real source',"
                        + " false, 'OK') on conflict do nothing")
                .update();
        // Isolation (U4 Code Generation): a collector left running by another cached context may mark this source
        // FAILED or disable it; each seeding brings it back, so the signals read by the barrier stay known.
        jdbc.sql("update data_source set enabled = true, status = 'OK', failure_category = null"
                        + " where source_id = 'u3-real' and (not enabled or status <> 'OK')")
                .update();
        return "u3-real";
    }

    /** M4 over the stored observations; only the kind of the active source is set by the test. */
    public static final class ControllableRealMarket implements MarketDataQuery {

        final AtomicReference<SourceKind> kind = new AtomicReference<>(SourceKind.REAL);
        private final JdbcMarketDataQuery stored;

        ControllableRealMarket(JdbcMarketDataQuery stored) {
            this.stored = stored;
        }

        public void simulated() {
            kind.set(SourceKind.SIMULATED);
        }

        @Override
        public SourceKind activeSourceKind() {
            return kind.get();
        }

        @Override
        public MarketValue referencePrice(TokenId token) {
            return stored.referencePrice(token);
        }

        @Override
        public Map<TokenId, MarketValue> referencePrices(Set<TokenId> tokens) {
            return stored.referencePrices(tokens);
        }

        @Override
        public MarketValue liquidity(TokenId token) {
            return stored.liquidity(token);
        }

        @Override
        public MarketValue volume24h(TokenId token) {
            return stored.volume24h(token);
        }

        @Override
        public MarketValue decimals(TokenId token) {
            return stored.decimals(token);
        }

        @Override
        public Map<TokenId, MarketValue> decimalsOf(Set<TokenId> tokens) {
            return stored.decimalsOf(tokens);
        }

        @Override
        public PriceVolumeWindow window(TokenId token, Duration length) {
            return stored.window(token, length);
        }

        @Override
        public List<SourceStatus> sourceStatuses() {
            return stored.sourceStatuses();
        }
    }

    /** The indicator of the scanner: Available, except for the mints marked unassessed. */
    public static final class ControllableAssessment implements RiskAssessmentQuery {

        final Set<String> unassessed = ConcurrentHashMap.newKeySet();

        public void unassessed(String mint) {
            unassessed.add(mint);
        }

        public void assessAll() {
            unassessed.clear();
        }

        @Override
        public AssessmentResult assess(TokenId token) {
            return unassessed.contains(token.mint())
                    ? new AssessmentResult.Unavailable(UnavailableReason.INSUFFICIENT_COVERAGE)
                    : new AssessmentResult.Available(25, RiskBand.LOW, 1);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class RealRiskTestConfig {

        @Bean
        @Primary
        ControllableRealMarket controllableRealMarket(JdbcMarketDataQuery stored) {
            return new ControllableRealMarket(stored);
        }

        @Bean
        @Primary
        ControllableAssessment controllableAssessment() {
            return new ControllableAssessment();
        }
    }
}
