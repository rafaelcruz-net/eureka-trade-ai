package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.ChallengeState;
import com.memetrade.botwallet.domain.ConsentChallenge;
import com.memetrade.botwallet.domain.ConsentKind;
import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.domain.OperationMode;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.RealRelease;
import com.memetrade.botwallet.domain.Withdrawal;
import com.memetrade.botwallet.domain.WithdrawalKind;
import com.memetrade.botwallet.domain.WithdrawalState;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC adapter of the bot wallet tables; every read of a person's data filters by tenant and person (BR6.4). */
@Repository
public class JdbcBotWalletRepository implements BotWalletRepository {

    static final String ACCOUNT_COLUMNS = "bot_wallet_id, tenant_id, user_id, public_address, main_wallet_address, "
            + "network, status, deposit_cursor, policy_reset_required, created_at";
    static final String WITHDRAWAL_COLUMNS = "withdrawal_id, tenant_id, bot_wallet_id, kind, lamports, fee_lamports, "
            + "state, idempotency_key, signing_request_id, blockhash, last_valid_height, signed_transaction, "
            + "message_digest, tx_signature, failure_code, attempts, next_attempt_at, requested_at, updated_at";

    private final JdbcClient jdbc;

    public JdbcBotWalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BotWalletAccount> activeWallet(UUID tenantId, UUID userId) {
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from bot_wallet_account "
                        + "where tenant_id = :tenant and user_id = :user and status = 'ACTIVE'")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(JdbcBotWalletRepository::account)
                .optional();
    }

    @Override
    public Optional<BotWalletAccount> lockActiveWallet(UUID tenantId, UUID userId) {
        jdbc.sql("set local lock_timeout = '2s'").update();
        return jdbc.sql("select " + ACCOUNT_COLUMNS + " from bot_wallet_account "
                        + "where tenant_id = :tenant and user_id = :user and status = 'ACTIVE' for update")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(JdbcBotWalletRepository::account)
                .optional();
    }

    @Override
    public Preferences preferences(UUID botWalletId) {
        return jdbc.sql("""
                        select operation_mode, cap_lamports, max_cap_lamports, max_slippage_bps, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, paused_reasons, updated_at
                        from real_preferences where bot_wallet_id = :id""")
                .param("id", botWalletId)
                .query((rs, row) -> new Preferences(
                        OperationMode.valueOf(rs.getString("operation_mode")),
                        rs.getLong("cap_lamports"),
                        rs.getLong("max_cap_lamports"),
                        rs.getInt("max_slippage_bps"),
                        rs.getLong("fee_reserve_lamports"),
                        rs.getLong("max_withdrawal_fee_lamports"),
                        reasons(rs.getArray("paused_reasons")),
                        instant(rs, "updated_at")))
                .single();
    }

    @Override
    public void savePreferences(UUID botWalletId, Preferences preferences) {
        // 261113-trading-real-bot U3 (M1, BR4.5; review R-06): the person owns only the USER reason. This statement
        // adds
        // or removes USER on the row AS IT IS NOW and keeps every other reason (DAILY_LOSS, KILL_SWITCH...) and its
        // start untouched, so a pause written by pauseUser between the read of the preferences and this save is never
        // lost. USER keeps its start while it stays paused and starts at this change when it is new.
        jdbc.sql("""
                        update real_preferences p set operation_mode = :mode, cap_lamports = :cap,
                          max_slippage_bps = :slippage, updated_at = :at,
                          paused_reasons = array(
                            select r from unnest(p.paused_reasons) as r where r <> 'USER'
                            union select 'USER' where cast(:userPaused as boolean)
                            order by 1),
                          paused_since = (p.paused_since - 'USER') || case
                            when cast(:userPaused as boolean) then jsonb_build_object('USER', case
                              when 'USER' = any (p.paused_reasons)
                                then coalesce(p.paused_since ->> 'USER', cast(:atText as text))
                              else cast(:atText as text) end)
                            else '{}'::jsonb end
                        where bot_wallet_id = :id""")
                .param("atText", preferences.updatedAt().toString())
                .param("mode", preferences.operationMode().name())
                .param("cap", preferences.capLamports())
                .param("slippage", preferences.maxSlippageBps())
                .param("userPaused", preferences.pausedReasons().contains(PauseReason.USER))
                .param("at", Timestamp.from(preferences.updatedAt()))
                .param("id", botWalletId)
                .update();
    }

    @Override
    public void insertWallet(BotWalletAccount wallet, Preferences preferences, AcceptedConsent consent) {
        jdbc.sql("insert into bot_wallet_account (" + ACCOUNT_COLUMNS + ") values (:id, :tenant, :user, :address, "
                        + ":main, :network, 'ACTIVE', null, false, :at)")
                .param("id", wallet.botWalletId())
                .param("tenant", wallet.tenantId())
                .param("user", wallet.userId())
                .param("address", wallet.publicAddress())
                .param("main", wallet.mainWalletAddress())
                .param("network", wallet.network().name())
                .param("at", Timestamp.from(wallet.createdAt()))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, max_cap_lamports,
                          max_slippage_bps, fee_reserve_lamports, max_withdrawal_fee_lamports, paused_reasons, updated_at)
                        values (:id, :mode, :cap, :maxCap, :slippage, :reserve, :maxFee, '{}', :at)""")
                .param("id", wallet.botWalletId())
                .param("mode", preferences.operationMode().name())
                .param("cap", preferences.capLamports())
                .param("maxCap", preferences.maxCapLamports())
                .param("slippage", preferences.maxSlippageBps())
                .param("reserve", preferences.feeReserveLamports())
                .param("maxFee", preferences.maxWithdrawalFeeLamports())
                .param("at", Timestamp.from(preferences.updatedAt()))
                .update();
        insertConsent(wallet, consent);
    }

    private void insertConsent(BotWalletAccount wallet, AcceptedConsent consent) {
        jdbc.sql("""
                        insert into wallet_consent (consent_id, tenant_id, bot_wallet_id, kind, nonce, expires_at,
                          authorized_mode, authorized_cap_lamports, signed_message_digest, signed_at)
                        values (:id, :tenant, :wallet, :kind, :nonce, :expires, :mode, :cap, :digest, :signed)""")
                .param("id", consent.consentId())
                .param("tenant", wallet.tenantId())
                .param("wallet", wallet.botWalletId())
                .param("kind", consent.kind().name())
                .param("nonce", consent.nonce())
                .param("expires", Timestamp.from(consent.expiresAt()))
                .param("mode", consent.mode().name())
                .param("cap", consent.capLamports())
                .param("digest", consent.digest())
                .param("signed", Timestamp.from(consent.signedAt()))
                .update();
    }

    @Override
    public void insertChallenge(ConsentChallenge challenge) {
        jdbc.sql("""
                        insert into consent_challenge (challenge_id, tenant_id, user_id, kind, message, nonce, mode,
                          cap_lamports, issued_at, expires_at, state)
                        values (:id, :tenant, :user, :kind, :message, :nonce, :mode, :cap, :issued, :expires, 'PENDING')""")
                .param("id", challenge.challengeId())
                .param("tenant", challenge.tenantId())
                .param("user", challenge.userId())
                .param("kind", challenge.kind().name())
                .param("message", challenge.message())
                .param("nonce", challenge.nonce())
                .param("mode", challenge.mode().name())
                .param("cap", challenge.capLamports())
                .param("issued", Timestamp.from(challenge.issuedAt()))
                .param("expires", Timestamp.from(challenge.expiresAt()))
                .update();
    }

    @Override
    public Optional<ConsentChallenge> challenge(UUID tenantId, UUID userId, UUID challengeId) {
        return jdbc.sql("""
                        select challenge_id, tenant_id, user_id, kind, message, nonce, mode, cap_lamports, issued_at,
                          expires_at, state, in_use_since, consumed_at
                        from consent_challenge where challenge_id = :id and tenant_id = :tenant and user_id = :user""")
                .param("id", challengeId)
                .param("tenant", tenantId)
                .param("user", userId)
                .query((rs, row) -> new ConsentChallenge(
                        rs.getObject("challenge_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        ConsentKind.valueOf(rs.getString("kind")),
                        rs.getString("message"),
                        rs.getString("nonce"),
                        OperationMode.valueOf(rs.getString("mode")),
                        rs.getLong("cap_lamports"),
                        instant(rs, "issued_at"),
                        instant(rs, "expires_at"),
                        ChallengeState.valueOf(rs.getString("state")),
                        instant(rs, "in_use_since"),
                        instant(rs, "consumed_at")))
                .optional();
    }

    @Override
    public void updateChallenge(ConsentChallenge challenge) {
        jdbc.sql("update consent_challenge set state = :state, in_use_since = :inUse, consumed_at = :consumed "
                        + "where challenge_id = :id")
                .param("state", challenge.state().name())
                .param("inUse", challenge.inUseSince() == null ? null : Timestamp.from(challenge.inUseSince()))
                .param("consumed", challenge.consumedAt() == null ? null : Timestamp.from(challenge.consumedAt()))
                .param("id", challenge.challengeId())
                .update();
    }

    @Override
    public boolean useNonce(String nonce, Instant purgeAfter) {
        return jdbc.sql("insert into used_nonce (nonce, purge_after) values (:nonce, :purge) on conflict do nothing")
                        .param("nonce", nonce)
                        .param("purge", Timestamp.from(purgeAfter))
                        .update()
                == 1;
    }

    @Override
    public List<RealRelease> releases(UUID userId) {
        // 261113-trading-real-bot U5 (BR1.1): with the state of the release; only an ACTIVE one is in force.
        return jdbc.sql("select " + JdbcRealOpsStore.RELEASE_COLUMNS + " from real_release where user_id = :user")
                .param("user", userId)
                .query(JdbcRealOpsStore::release)
                .list();
    }

    @Override
    public Optional<Long> lastCapLoweredByAdmin(UUID botWalletId) {
        // 261113-trading-real-bot U6 (BR5.4): only the administrator enqueues LOWER_CAP (CapService); the person's own
        // lowering goes straight to the TxSigner.
        return jdbc.sql("""
                        select payload ->> 'capLamports' from real_pending_txsigner_command
                        where bot_wallet_id = :wallet and kind = 'LOWER_CAP'
                        order by created_at desc, command_id desc limit 1""")
                .param("wallet", botWalletId)
                .query(String.class)
                .optional()
                .map(Long::parseLong);
    }

    @Override
    public void insertRelease(RealRelease release) {
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          state)
                        values (:id, :user, :network, :by, :requested, :effective, :state)""")
                .param("id", release.releaseId())
                .param("user", release.userId())
                .param("network", release.network().name())
                .param("by", release.requestedBy())
                .param("requested", Timestamp.from(release.requestedAt()))
                .param("effective", Timestamp.from(release.effectiveAt()))
                .param("state", release.state().name())
                .update();
    }

    @Override
    public void insertWithdrawal(Withdrawal withdrawal) {
        jdbc.sql("insert into withdrawal (" + WITHDRAWAL_COLUMNS + ") values (:id, :tenant, :wallet, :kind, :lamports, "
                        + ":fee, :state, :key, :signing, null, null, null, null, null, null, 0, :next, :requested, "
                        + ":updated)")
                .param("id", withdrawal.withdrawalId())
                .param("tenant", withdrawal.tenantId())
                .param("wallet", withdrawal.botWalletId())
                .param("kind", withdrawal.kind().name())
                .param("lamports", withdrawal.lamports())
                .param("fee", withdrawal.feeLamports())
                .param("state", withdrawal.state().name())
                .param("key", withdrawal.idempotencyKey())
                .param("signing", withdrawal.signingRequestId())
                .param("next", Timestamp.from(withdrawal.nextAttemptAt()))
                .param("requested", Timestamp.from(withdrawal.requestedAt()))
                .param("updated", Timestamp.from(withdrawal.updatedAt()))
                .update();
    }

    @Override
    public Optional<Withdrawal> withdrawal(UUID tenantId, UUID botWalletId, UUID withdrawalId) {
        return jdbc.sql("select " + WITHDRAWAL_COLUMNS + " from withdrawal "
                        + "where withdrawal_id = :id and tenant_id = :tenant and bot_wallet_id = :wallet")
                .param("id", withdrawalId)
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .query(JdbcBotWalletRepository::withdrawal)
                .optional();
    }

    @Override
    public List<Withdrawal> withdrawals(UUID tenantId, UUID botWalletId, int limit) {
        return jdbc.sql("select " + WITHDRAWAL_COLUMNS + " from withdrawal where tenant_id = :tenant "
                        + "and bot_wallet_id = :wallet order by requested_at desc, withdrawal_id desc limit :limit")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .param("limit", limit)
                .query(JdbcBotWalletRepository::withdrawal)
                .list();
    }

    @Override
    public boolean hasAmbiguousWithdrawal(UUID botWalletId) {
        return jdbc.sql(
                        "select exists (select 1 from withdrawal where bot_wallet_id = :wallet and state = 'AMBIGUOUS')")
                .param("wallet", botWalletId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<UUID> withdrawalIdByKey(UUID tenantId, UUID idempotencyKey) {
        return jdbc.sql("select withdrawal_id from withdrawal where tenant_id = :tenant and idempotency_key = :key")
                .param("tenant", tenantId)
                .param("key", idempotencyKey)
                .query(UUID.class)
                .optional();
    }

    @Override
    public List<DepositView> deposits(UUID tenantId, UUID botWalletId, int limit) {
        return jdbc.sql("""
                        select tx_signature, lamports, finalized_at, known_source from observed_deposit
                        where tenant_id = :tenant and bot_wallet_id = :wallet
                        order by finalized_at desc, tx_signature desc limit :limit""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .param("limit", limit)
                .query((rs, row) -> new DepositView(
                        rs.getString("tx_signature"),
                        rs.getLong("lamports"),
                        instant(rs, "finalized_at"),
                        rs.getBoolean("known_source")))
                .list();
    }

    @Override
    public int releaseStuckChallenges(Instant inUseBefore) {
        return jdbc.sql("update consent_challenge set state = 'PENDING', in_use_since = null "
                        + "where state = 'IN_USE' and in_use_since < :before")
                .param("before", Timestamp.from(inUseBefore))
                .update();
    }

    @Override
    public Map<PauseReason, Instant> pausedSince(UUID botWalletId) {
        Map<PauseReason, Instant> since = new EnumMap<>(PauseReason.class);
        jdbc.sql("""
                        select s.key, s.value from real_preferences p, jsonb_each_text(p.paused_since) s
                        where p.bot_wallet_id = :id and s.key = any (p.paused_reasons)""").param("id", botWalletId).query(rs -> {
            since.put(PauseReason.valueOf(rs.getString("key")), Instant.parse(rs.getString("value")));
        });
        return since;
    }

    @Override
    public boolean addPause(UUID botWalletId, PauseReason reason, Instant at) {
        return jdbc.sql("""
                                update real_preferences
                                set paused_reasons = array_append(paused_reasons, :reason),
                                    paused_since = paused_since || jsonb_build_object(cast(:reason as text), cast(:atText as text)),
                                    updated_at = :at
                                where bot_wallet_id = :id and not (:reason = any (paused_reasons))""")
                        .param("reason", reason.name())
                        .param("atText", at.toString())
                        .param("at", Timestamp.from(at))
                        .param("id", botWalletId)
                        .update()
                == 1;
    }

    @Override
    public boolean removePause(UUID botWalletId, PauseReason reason) {
        return jdbc.sql("""
                                update real_preferences
                                set paused_reasons = array_remove(paused_reasons, :reason),
                                    paused_since = paused_since - cast(:reason as text)
                                where bot_wallet_id = :id and :reason = any (paused_reasons)""")
                        .param("reason", reason.name())
                        .param("id", botWalletId)
                        .update()
                == 1;
    }

    @Override
    public List<BotWalletAccount> walletsWithReleaseInForce(Network network, Instant now) {
        // 261113-trading-real-bot U5 (BR1.1): in force only with an ACTIVE release (the gates set it after 24 hours).
        return jdbc.sql("select " + prefixed("a") + " from bot_wallet_account a where a.status = 'ACTIVE' "
                        + "and exists (select 1 from real_release r where r.user_id = a.user_id "
                        + "and r.network = :network and r.state = 'ACTIVE' and r.revoked_at is null) "
                        + "order by a.bot_wallet_id")
                .param("network", network.name())
                .query(JdbcBotWalletRepository::account)
                .list();
    }

    @Override
    public List<PausedWalletRow> walletsPausedFor(PauseReason reason) {
        return jdbc.sql("""
                        select a.tenant_id, a.user_id, a.bot_wallet_id, p.paused_since ->> :reason as since,
                          p.updated_at
                        from bot_wallet_account a join real_preferences p on p.bot_wallet_id = a.bot_wallet_id
                        where a.status = 'ACTIVE' and :reason = any (p.paused_reasons)
                        order by a.bot_wallet_id""")
                .param("reason", reason.name())
                .query((rs, row) -> {
                    String since = rs.getString("since");
                    return new PausedWalletRow(
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("bot_wallet_id", UUID.class),
                            since == null ? instant(rs, "updated_at") : Instant.parse(since));
                })
                .list();
    }

    private static String prefixed(String alias) {
        return java.util.Arrays.stream(ACCOUNT_COLUMNS.split(","))
                .map(column -> alias + "." + column.trim())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    static BotWalletAccount account(ResultSet rs, int row) throws SQLException {
        return new BotWalletAccount(
                rs.getObject("bot_wallet_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("public_address"),
                rs.getString("main_wallet_address"),
                Network.valueOf(rs.getString("network")),
                rs.getString("status"),
                rs.getString("deposit_cursor"),
                rs.getBoolean("policy_reset_required"),
                instant(rs, "created_at"));
    }

    static Withdrawal withdrawal(ResultSet rs, int row) throws SQLException {
        Long validHeight = rs.getObject("last_valid_height", Long.class);
        return new Withdrawal(
                rs.getObject("withdrawal_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("bot_wallet_id", UUID.class),
                WithdrawalKind.valueOf(rs.getString("kind")),
                rs.getLong("lamports"),
                rs.getLong("fee_lamports"),
                WithdrawalState.valueOf(rs.getString("state")),
                rs.getObject("idempotency_key", UUID.class),
                rs.getObject("signing_request_id", UUID.class),
                rs.getString("blockhash"),
                validHeight,
                rs.getString("signed_transaction"),
                rs.getString("message_digest"),
                rs.getString("tx_signature"),
                rs.getString("failure_code"),
                rs.getInt("attempts"),
                instant(rs, "next_attempt_at"),
                instant(rs, "requested_at"),
                instant(rs, "updated_at"));
    }

    private static EnumSet<PauseReason> reasons(Array array) throws SQLException {
        EnumSet<PauseReason> reasons = EnumSet.noneOf(PauseReason.class);
        if (array != null) {
            Arrays.stream((String[]) array.getArray()).map(PauseReason::valueOf).forEach(reasons::add);
        }
        return reasons;
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
