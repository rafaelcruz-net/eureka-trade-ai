package com.memetrade.botwallet;

import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Base of the integration tests of the U5 (261113-trading-real-bot): a signed-in person with an ACTIVE bot wallet, the
 * REAL on, and helpers that write the rows the U1, U3 and U4 would write. The annotations are the same as
 * {@link RealStateQueryIT}, so every test of the U5 shares that one cached Spring context. Jobs of the worker are built
 * in the tests from the beans of this context with recorded fakes (CI, network); nothing calls a real provider.
 */
@TestPropertySource(properties = "memetrade.real.enabled=true")
@ActiveProfiles("local")
public abstract class AbstractRealOpsIT extends AbstractRealIT {

    /** A signed-in person (main wallet generated in test code), with an ACTIVE bot wallet. */
    protected record OpsPerson(Person person, UUID botWalletId, String botAddress) {
        public UUID tenantId() {
            return person.tenantId();
        }

        public UUID userId() {
            return person.userId();
        }

        public String mainAddress() {
            return person.wallet().address();
        }
    }

    /** Every test leaves the global switches off, whatever it did (the tests never depend on each other). */
    @AfterEach
    void resetGlobalSwitches() {
        jdbc.sql("update real_kill_switch set active = false, activated_at = null, activated_by = null, reason = null,"
                        + " version = version + 1, updated_at = now() where active")
                .update();
        jdbc.sql("update real_restore_marker set cleared_at = now() where cleared_at is null")
                .update();
    }

    protected OpsPerson activePerson() throws Exception {
        Person person = signIn(true, false);
        UUID botWalletId = UUID.randomUUID();
        String botAddress = TestWallet.generate().address();
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", botAddress)
                .param("main", person.wallet().address())
                .param("at", Timestamp.from(now))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, 'AUTO_FULL', 10000000000, 30000000, 100000, :at)""").param("id", botWalletId).param("at", Timestamp.from(now)).update();
        return new OpsPerson(person, botWalletId, botAddress);
    }

    /** A release of {@code state} on {@code network}, requested {@code ago} before the clock. */
    protected UUID releaseOf(UUID userId, String network, String state, Duration ago) {
        UUID id = UUID.randomUUID();
        Instant requested = clock.instant().minus(ago);
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          state, blocked_reason, revoked_at)
                        values (:id, :user, :network, :user, :requested, :effective, :state,
                          case when :state = 'BLOCKED' then 'CI_RED' end,
                          case when :state = 'REVOKED' then cast(:requested as timestamptz) end)""")
                .param("id", id)
                .param("user", userId)
                .param("network", network)
                .param("requested", Timestamp.from(requested))
                .param("effective", Timestamp.from(requested.plus(Duration.ofHours(24))))
                .param("state", state)
                .update();
        return id;
    }

    protected String releaseState(UUID releaseId) {
        return jdbc.sql("select state from real_release where release_id = :id")
                .param("id", releaseId)
                .query(String.class)
                .single();
    }

    /** A REAL buy of a bot of the person in {@code state} (as the U4 records it), without its reservation. */
    protected UUID order(OpsPerson person, String state) {
        UUID id = UUID.randomUUID();
        boolean signed = !"PROPOSED".equals(state) && !"QUOTED".equals(state);
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_wallet_id, bot_id, origin,
                          idempotency_key, side, mint, input_lamports, operation_mode, signing_mode, max_slippage_bps,
                          reason, state, tx_signature, created_at, updated_at)
                        values (:id, :t, :u, :w, gen_random_uuid(), 'BOT', :key, 'BUY', :mint, 50000000, 'AUTO_FULL',
                          'AUTO_FULL', 200, 'sinal', :state, :sig, :at, :at)""")
                .param("id", id)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("w", person.botWalletId())
                .param("key", id.toString())
                .param("mint", SyntheticIds.mint())
                .param("state", state)
                .param("sig", signed ? SyntheticIds.signature() : null)
                .param("at", Timestamp.from(clock.instant()))
                .update();
        return id;
    }

    protected String orderState(UUID orderId) {
        return jdbc.sql("select state || coalesce(':' || failure_code, '') from real_order where real_order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    /** A finalized deposit of {@code lamports} recorded in the REAL ledger, as the deposit scan of the U1 writes it. */
    protected void deposit(OpsPerson person, long lamports) {
        String signature = SyntheticIds.signature();
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                          tx_signature, reference, idempotency_key, created_at)
                        values (gen_random_uuid(), :t, :w, 'DEPOSIT', :lamports, :sig, :ref, :ref, :at)""")
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .param("lamports", lamports)
                .param("sig", signature)
                .param("ref", "dep:" + signature)
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    /** Revokes every open release of a network in the shared database, so a count of the pilot starts from zero. */
    protected void revokeEveryOpenRelease(String network) {
        jdbc.sql("update real_release set revoked_at = now() where network = :n and revoked_at is null")
                .param("n", network)
                .update();
    }
}
