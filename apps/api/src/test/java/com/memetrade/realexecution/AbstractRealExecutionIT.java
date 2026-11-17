package com.memetrade.realexecution;

import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.infra.AmbiguousJob;
import com.memetrade.realexecution.infra.ConfirmationJob;
import com.memetrade.realexecution.infra.DecisionConsumer;
import com.memetrade.realexecution.infra.LiquidationJob;
import com.memetrade.realexecution.infra.OrderDriver;
import com.memetrade.realexecution.infra.SigningDeadlineJob;
import com.memetrade.realexecution.infra.UpstreamHealthJob;
import com.memetrade.riskcontrol.real.AbstractRealRiskIT;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Base of the integration tests of the REAL execution (U4): real PostgreSQL with the migrations of the application, the
 * REAL barrier of the U3, the ledger and the bot wallet of the U1, and three doubles at the edges of the system:
 *
 * <ul>
 *   <li>the network ({@link FakeRealChain}, M3), which records every send and answers what the test scripts;
 *   <li>the Jupiter ({@link FakeSwapRouter}), with a synthetic route for the bot wallet of the test;
 *   <li>the TxSigner in process ({@link com.memetrade.botwallet.FakeTxSigner}), which signs with the TEST key of the bot
 *       wallet and is idempotent by signingRequestId like the real one (its policy is tested in apps/txsigner).
 * </ul>
 *
 * The jobs of the worker are driven directly by the tests ({@code runOnce}); the crash points emulate the death of the
 * worker after each recorded step: the next run, with the lease expired, resumes from what the database holds.
 */
@Import(AbstractRealExecutionIT.ExecutionTestConfig.class)
public abstract class AbstractRealExecutionIT extends AbstractRealRiskIT {

    @Autowired
    protected FakeRealChain chain;

    @Autowired
    protected FakeSwapRouter router;

    @Autowired
    protected FakeCrashPoints crashes;

    @Autowired
    protected RealExecutionPort port;

    @Autowired
    protected RealDecisionStep decisionStep;

    @Autowired
    protected DecisionConsumer consumer;

    @Autowired
    protected OrderDriver driver;

    @Autowired
    protected ConfirmationJob confirmation;

    @Autowired
    protected AmbiguousJob ambiguous;

    @Autowired
    protected SigningDeadlineJob deadlines;

    @Autowired
    protected UpstreamHealthJob health;

    @Autowired
    protected LiquidationJob liquidations;

    @BeforeEach
    void resetEdges() {
        chain.reset();
        router.reset();
        crashes.reset();
        // The shared test clock starts where it starts in an isolated run: other suites of the same context advance it
        // (DashboardStreamIT by 16 minutes, RealStateQueryIT by 11), and no test here depends on what they left.
        clock.set(com.memetrade.testsupport.MutableClock.DEFAULT_START);
        // Isolation (team.md: no test depends on another): the orders and decisions left open by earlier tests are
        // parked, so the jobs of this test only see what this test creates.
        jdbc.sql("update real_order set lease_owner = 'parked-by-test', lease_until = 'infinity'"
                        + " where state in ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS')")
                .update();
        jdbc.sql("update real_decision_request set state = 'DISCARDED', discard_reason = 'STATE_CHANGED',"
                        + " processed_at = now() where state = 'PENDING'")
                .update();
        jdbc.sql("update real_liquidation set state = 'WITHDRAWING', updated_at = now() where state = 'SELLING'")
                .update();
        jdbc.sql("update real_liquidation set state = 'PARTIAL', updated_at = now() where state = 'WITHDRAWING'")
                .update();
        jdbc.sql("update real_upstream_health set healthy_since = null"
                        + " where subject_id = '00000000-0000-0000-0000-000000000000'")
                .update();
    }

    /** A person with a REAL bot, its ACTIVE bot wallet (test keys), money, and a token with a fresh price. */
    public record Setup(
            UUID tenantId, UUID userId, UUID botWalletId, UUID botId, TestWallet bot, TestWallet main, String mint) {}

    protected Setup realBot(String mode) {
        return realBot(mode, 5_000_000_000L);
    }

    protected Setup realBot(String mode, long capLamports) {
        TestWallet main = TestWallet.generate();
        TestWallet bot = TestWallet.generate();
        signer.register(bot);
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, main.address());
        UUID botWalletId = UUID.randomUUID();
        Instant created = clock.instant().minus(Duration.ofDays(2));
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", bot.address())
                .param("main", main.address())
                .param("at", Timestamp.from(created))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, :mode, :cap, 30000000, 100000, :at)""")
                .param("id", botWalletId)
                .param("mode", mode)
                .param("cap", capLamports)
                .param("at", Timestamp.from(created))
                .update();
        release(person.userId());
        UUID botId = realBotRow(person.tenantId(), person.userId());
        Wallet wallet = new Wallet(person.tenantId(), person.userId(), botWalletId, botId);
        deposit(wallet, 3 * SOL);
        solAt("150.00", 5);
        String mint = token("0.0001", 6, 5);
        return new Setup(person.tenantId(), person.userId(), botWalletId, botId, bot, main, mint);
    }

    /** An ACTIVE REAL bot row of the person (validated through the migration constraints only). */
    protected UUID realBotRow(UUID tenantId, UUID userId) {
        UUID botId = UUID.randomUUID();
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", tenantId)
                .param("u", userId)
                .update();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, 'robo real', 'MOMENTUM_VOLUME', '1', 'ACTIVE', now(), now(), 'REAL')""").param("b", botId).param("t", tenantId).param("u", userId).update();
        return botId;
    }

    protected Wallet wallet(Setup setup) {
        return new Wallet(setup.tenantId(), setup.userId(), setup.botWalletId(), setup.botId());
    }

    /** The decision of the cycle for a buy of {@code lamports}, through the barrier, recorded as PENDING. */
    protected UUID pendingBuy(Setup setup, long lamports) {
        RealDecisionStep.Outcome outcome =
                transactions.execute(status -> decisionStep.submit(RealDecisionStep.Candidate.buy(
                        setup.tenantId(),
                        setup.userId(),
                        setup.botId(),
                        UUID.randomUUID(),
                        setup.mint(),
                        lamports,
                        "sinal de compra")));
        if (outcome.decisionRequestId().isEmpty()) {
            String code = jdbc.sql("select coalesce(max(code || ' ' || coalesce(detail::text, '')), 'none') from"
                            + " real_risk_evaluation where user_id = :u")
                    .param("u", setup.userId())
                    .query(String.class)
                    .single();
            throw new IllegalStateException("The buy was not recorded: " + outcome.result() + " (" + code + ")");
        }
        return outcome.decisionRequestId().get();
    }

    /** The order created from the decision request, after one round of the consumer. */
    protected UUID orderOf(UUID decisionRequestId) {
        return jdbc.sql("select real_order_id from real_order where idempotency_key = :key")
                .param("key", decisionRequestId.toString())
                .query(UUID.class)
                .single();
    }

    protected String state(UUID orderId) {
        return jdbc.sql("select state from real_order where real_order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    protected String failureCode(UUID orderId) {
        return jdbc.sql("select failure_code from real_order where real_order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    protected String txSignature(UUID orderId) {
        return jdbc.sql("select tx_signature from real_order where real_order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    /** The lease of the order expired, as after the death of the worker that held it (10 s later). */
    protected void leaseExpired(UUID orderId) {
        jdbc.sql("update real_order set lease_until = now() - interval '1 second' where real_order_id = :id")
                .param("id", orderId)
                .update();
    }

    /** The ledger entries of the order, by kind, with their lamports. */
    protected java.util.Map<String, Long> entries(UUID orderId) {
        java.util.Map<String, Long> entries = new java.util.TreeMap<>();
        jdbc.sql("select entry_kind, sum(lamports) as lamports from real_ledger_entry where reference = :ref"
                        + " group by entry_kind")
                .param("ref", "order:" + orderId)
                .query((rs, row) -> entries.put(
                        rs.getString("entry_kind"), rs.getBigDecimal("lamports").longValue()))
                .list();
        return entries;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ExecutionTestConfig {

        @Bean
        @Primary
        FakeRealChain fakeRealChain() {
            return new FakeRealChain();
        }

        @Bean
        @Primary
        FakeSwapRouter fakeSwapRouter() {
            return new FakeSwapRouter();
        }

        @Bean
        @Primary
        FakeCrashPoints fakeCrashPoints() {
            return new FakeCrashPoints();
        }
    }
}
