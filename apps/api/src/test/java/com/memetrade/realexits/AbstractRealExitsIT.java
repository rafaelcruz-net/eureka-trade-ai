package com.memetrade.realexits;

import com.memetrade.botwallet.api.ExitsAvailability;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.testsupport.SyntheticIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Base of the integration tests of the U7 (conditional exits): the context of the REAL execution of the U4 (real
 * PostgreSQL with the migrations, the barrier, the ledger, the fake network and the fake TxSigner) plus two doubles:
 *
 * <ul>
 *   <li>the Jupiter Trigger API ({@link ScriptedTriggerPort}), scripted per conditional, never the real one;
 *   <li>the exits flag ({@link SwitchableExitsAvailability}), ON here and switched OFF by the tests of BR9.3; the flag
 *       of the deployments is off by default ({@code RealExitsSettingsTest}).
 * </ul>
 *
 * Every test creates its own people, wallets, positions and conditionals; the conditionals left open by earlier tests
 * are closed first so the jobs of a test only see what it creates.
 */
@Import(AbstractRealExitsIT.ExitsTestConfig.class)
public abstract class AbstractRealExitsIT extends AbstractRealExecutionIT {

    @Autowired
    protected ScriptedTriggerPort trigger;

    @Autowired
    protected SwitchableExitsAvailability exits;

    @BeforeEach
    void resetExits() {
        trigger.reset();
        exits.enabled.set(true);
        // Isolation: what earlier tests left not final is parked as EXPIRED (a final state the jobs never read).
        jdbc.sql("update real_conditional_order set state = 'CANCELLING', cancel_reason = 'USER',"
                        + " cancelling_since = now() where state = 'OPEN'")
                .update();
        jdbc.sql("update real_conditional_order set state = 'CANCELLED', cancel_reason = coalesce(cancel_reason,"
                        + " 'USER'), final_at = now() where state in ('PENDING_SIGNATURE', 'CANCELLING')")
                .update();
    }

    /** A signed-in person (its access token) with the REAL bot wallet of {@link #realBot(String)} and a position. */
    protected record Signed(Person person, Setup setup) {}

    protected Signed signedRealBot(String mode, long positionUnits) throws Exception {
        Person person = signIn(true, true);
        com.memetrade.testsupport.TestWallet bot = com.memetrade.testsupport.TestWallet.generate();
        signer.register(bot);
        UUID botWalletId = UUID.randomUUID();
        Instant created = clock.instant().minus(java.time.Duration.ofDays(2));
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", bot.address())
                .param("main", person.wallet().address())
                .param("at", Timestamp.from(created))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, :mode, 5000000000, 30000000, 100000, :at)""")
                .param("id", botWalletId)
                .param("mode", mode)
                .param("at", Timestamp.from(created))
                .update();
        UUID botId = realBotRow(person.tenantId(), person.userId());
        Wallet wallet = new Wallet(person.tenantId(), person.userId(), botWalletId, botId);
        deposit(wallet, 3 * SOL);
        solAt("150.00", 5);
        String mint = token("0.0001", 6, 5);
        if (positionUnits > 0) {
            position(wallet, mint, positionUnits, 1_000_000L);
        }
        return new Signed(
                person, new Setup(person.tenantId(), person.userId(), botWalletId, botId, bot, person.wallet(), mint));
    }

    /** A conditional of the setup on its mint, as the application writes it, in {@code state}. */
    protected UUID conditional(Setup setup, String state, long amount) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,
                          position_mint, kind, stop_price_usd, take_profit_price_usd, amount_base_units, slippage_bps,
                          state, adapter, jupiter_order_id, escrow_account, idempotency_key, signing_message,
                          signing_deadline, cancel_reason, cancelling_since, last_read_at, created_at, updated_at)
                        values (:id, :t, :u, :w, :m, 'OCO', 0.00008, 0.00015, :amount, 200, :state, 'JUPITER',
                          :provider, :escrow, :key, :message, :deadline, :reason, :since, :read, :at, :at)""")
                .param("id", id)
                .param("t", setup.tenantId())
                .param("u", setup.userId())
                .param("w", setup.botWalletId())
                .param("m", setup.mint())
                .param("amount", amount)
                .param("state", state)
                .param("provider", "PENDING_SIGNATURE".equals(state) ? null : "jup-" + id)
                .param("escrow", "PENDING_SIGNATURE".equals(state) ? null : SyntheticIds.mint())
                .param("key", id.toString())
                .param("message", "PENDING_SIGNATURE".equals(state) ? "consent" : null)
                .param("deadline", "PENDING_SIGNATURE".equals(state) ? Timestamp.from(now.plusSeconds(60)) : null)
                .param("reason", "CANCELLING".equals(state) ? "USER" : null)
                .param("since", "CANCELLING".equals(state) ? Timestamp.from(now) : null)
                .param("read", "PENDING_SIGNATURE".equals(state) ? null : Timestamp.from(now))
                .param("at", Timestamp.from(now))
                .update();
        return id;
    }

    protected String conditionalState(UUID id) {
        return jdbc.sql("select state from real_conditional_order where conditional_order_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    protected String cancelReason(UUID id) {
        return jdbc.sql("select cancel_reason from real_conditional_order where conditional_order_id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** The ledger entries of the conditional, by kind, with their lamports. */
    protected java.util.Map<String, Long> conditionalEntries(UUID id) {
        java.util.Map<String, Long> entries = new java.util.TreeMap<>();
        jdbc.sql("select entry_kind, sum(lamports) as lamports from real_ledger_entry where reference = :ref"
                        + " group by entry_kind")
                .param("ref", "conditional:" + id)
                .query((rs, row) -> entries.put(
                        rs.getString("entry_kind"), rs.getBigDecimal("lamports").longValue()))
                .list();
        return entries;
    }

    /** The exits flag of the tests (the deployments read memetrade.real.exits.enabled, off by default). */
    public static class SwitchableExitsAvailability implements ExitsAvailability {

        public final AtomicBoolean enabled = new AtomicBoolean(true);

        @Override
        public boolean exitsEnabled() {
            return enabled.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ExitsTestConfig {

        @Bean
        @Primary
        ScriptedTriggerPort scriptedTriggerPort() {
            return new ScriptedTriggerPort();
        }

        @Bean
        @Primary
        SwitchableExitsAvailability switchableExitsAvailability() {
            return new SwitchableExitsAvailability();
        }
    }
}
