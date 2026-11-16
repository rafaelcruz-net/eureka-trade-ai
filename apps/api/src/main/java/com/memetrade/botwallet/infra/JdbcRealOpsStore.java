package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.AdminViewStore;
import com.memetrade.botwallet.domain.AnonymizationStore;
import com.memetrade.botwallet.domain.BlockedReason;
import com.memetrade.botwallet.domain.CommandStore;
import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.domain.RealRelease;
import com.memetrade.botwallet.domain.ReconciliationOutcome;
import com.memetrade.botwallet.domain.ReconciliationStore;
import com.memetrade.botwallet.domain.ReleaseState;
import com.memetrade.botwallet.domain.ReleaseStore;
import com.memetrade.botwallet.domain.RestoreStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 261113-trading-real-bot U5: JDBC adapter of the releases, the reconciliations and the divergences of the REAL
 * operations (migration 0021). Each change of
 * state is a compare-and-set {@code UPDATE ... WHERE state = :expected} in the transaction of the caller (U5-NFR4.1);
 * the release of the MAINNET counts and inserts under one transaction-scoped advisory lock.
 */
@Repository
public class JdbcRealOpsStore
        implements ReleaseStore, ReconciliationStore, RestoreStore, CommandStore, AnonymizationStore, AdminViewStore {

    /** U5-NFR4.1: the key of the one lock of the releases on the MAINNET ({@code pg_advisory_xact_lock}). */
    static final long MAINNET_RELEASE_LOCK = 261_113_521_001L;

    static final String RELEASE_COLUMNS = "release_id, user_id, network, requested_by, requested_at, effective_at,"
            + " revoked_at, state, blocked_reason, gates_checked_at, revoked_by";

    private final JdbcClient jdbc;

    public JdbcRealOpsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ----- releases (BR1.2 to BR1.6) -----

    @Override
    public Optional<WalletOfPerson> activeWalletOf(UUID userId) {
        return jdbc.sql("select tenant_id, bot_wallet_id from bot_wallet_account where user_id = :u and status ="
                        + " 'ACTIVE'")
                .param("u", userId)
                .query((rs, row) -> new WalletOfPerson(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("bot_wallet_id", UUID.class)))
                .optional();
    }

    @Override
    public Optional<UUID> tenantOf(UUID userId) {
        return jdbc.sql("select tenant_id from bot_wallet_account where user_id = :u order by created_at desc limit 1")
                .param("u", userId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public void lockMainnetReleases() {
        jdbc.sql("select pg_advisory_xact_lock(:key)")
                .param("key", MAINNET_RELEASE_LOCK)
                .query()
                .singleRow();
    }

    @Override
    public boolean hasOpenRelease(UUID userId, Network network) {
        return jdbc.sql("select exists (select 1 from real_release where user_id = :u and network = :n and revoked_at"
                        + " is null)")
                .param("u", userId)
                .param("n", network.name())
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean hasActiveRelease(UUID userId, Network network) {
        return jdbc.sql("select exists (select 1 from real_release where user_id = :u and network = :n and state ="
                        + " 'ACTIVE' and revoked_at is null)")
                .param("u", userId)
                .param("n", network.name())
                .query(Boolean.class)
                .single();
    }

    @Override
    public long openGuestReleases(Network network, UUID founder) {
        return jdbc.sql("select count(*) from real_release where network = :n and revoked_at is null and user_id <>"
                        + " :founder")
                .param("n", network.name())
                .param("founder", founder)
                .query(Long.class)
                .single();
    }

    @Override
    public boolean insert(RealRelease release) {
        try {
            jdbc.sql("""
                            insert into real_release (release_id, user_id, network, requested_by, requested_at,
                              effective_at, state)
                            values (:id, :user, :network, :by, :requested, :effective, :state)""")
                    .param("id", release.releaseId())
                    .param("user", release.userId())
                    .param("network", release.network().name())
                    .param("by", release.requestedBy())
                    .param("requested", Timestamp.from(release.requestedAt()))
                    .param("effective", Timestamp.from(release.effectiveAt()))
                    .param("state", release.state().name())
                    .update();
            return true;
        } catch (DuplicateKeyException otherOpenRelease) {
            return false;
        }
    }

    @Override
    public List<UUID> revokeOpen(UUID userId, Instant at, UUID by) {
        return jdbc.sql("update real_release set revoked_at = :at, revoked_by = :by, state = 'REVOKED' where user_id ="
                        + " :u and revoked_at is null returning release_id")
                .param("at", Timestamp.from(at))
                .param("by", by)
                .param("u", userId)
                .query(UUID.class)
                .list();
    }

    @Override
    public List<RealRelease> awaitingGates(Instant now) {
        return jdbc.sql("select " + RELEASE_COLUMNS + " from real_release where revoked_at is null and ((state ="
                        + " 'QUARANTINE' and effective_at <= :now) or state = 'BLOCKED') order by effective_at,"
                        + " release_id")
                .param("now", Timestamp.from(now))
                .query(JdbcRealOpsStore::release)
                .list();
    }

    @Override
    public boolean moveGates(
            UUID releaseId, ReleaseState expected, ReleaseState next, BlockedReason reason, Instant at) {
        return jdbc.sql("update real_release set state = :next, blocked_reason = :reason, gates_checked_at = :at where"
                                + " release_id = :id and state = :expected and revoked_at is null")
                        .param("next", next.name())
                        .param("reason", reason == null ? null : reason.name())
                        .param("at", Timestamp.from(at))
                        .param("id", releaseId)
                        .param("expected", expected.name())
                        .update()
                == 1;
    }

    @Override
    public boolean hasOpenDivergence(UUID userId) {
        return jdbc.sql("select exists (select 1 from real_divergence d join bot_wallet_account a on a.bot_wallet_id ="
                        + " d.bot_wallet_id where a.user_id = :u and d.state in ('OPEN', 'COMPENSATED'))")
                .param("u", userId)
                .query(Boolean.class)
                .single();
    }

    // ----- reconciliations and divergences (BR2.1 to BR2.3) -----

    private static final String DIVERGENCE_COLUMNS = "divergence_id, run_id, last_run_id, tenant_id, bot_wallet_id,"
            + " lamports_diff, token_diffs::text as token_diffs, state, compensation_entry_ids::text as"
            + " compensation_entry_ids, compensated_at, opened_at";

    @Override
    public Optional<WalletTarget> target(UUID botWalletId) {
        return jdbc.sql("select tenant_id, user_id, bot_wallet_id, public_address, deposit_cursor from"
                        + " bot_wallet_account where bot_wallet_id = :w")
                .param("w", botWalletId)
                .query((rs, row) -> new WalletTarget(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("bot_wallet_id", UUID.class),
                        rs.getString("public_address"),
                        rs.getString("deposit_cursor")))
                .optional();
    }

    @Override
    public int withdrawalsInTransit(UUID botWalletId) {
        return jdbc.sql("select count(*) from withdrawal where bot_wallet_id = :w and state in ('SENT', 'AMBIGUOUS')")
                .param("w", botWalletId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void insertRun(RunRecord run) {
        ReconciliationOutcome outcome = run.outcome();
        jdbc.sql("""
                        insert into real_reconciliation_run (run_id, tenant_id, bot_wallet_id, trigger, slot,
                          chain_lamports, ledger_lamports, token_diffs, in_transit_orders, result, started_at,
                          finished_at)
                        values (:id, :t, :w, :trigger, :slot, :chain, :ledger, cast(:diffs as jsonb), :transit, :result,
                          :started, :finished)""")
                .param("id", run.runId())
                .param("t", run.tenantId())
                .param("w", run.botWalletId())
                .param("trigger", run.trigger().name())
                .param("slot", outcome.slot(), java.sql.Types.BIGINT)
                .param("chain", outcome.chainLamports(), java.sql.Types.BIGINT)
                .param("ledger", outcome.ledgerLamports(), java.sql.Types.BIGINT)
                .param("diffs", TokenDiffsJson.write(outcome.tokenDiffs()))
                .param("transit", outcome.inTransit())
                .param("result", outcome.result().name())
                .param("started", Timestamp.from(run.startedAt()))
                .param("finished", Timestamp.from(run.finishedAt()))
                .update();
    }

    @Override
    public Optional<DivergenceRow> openDivergence(UUID botWalletId) {
        return jdbc.sql("select " + DIVERGENCE_COLUMNS + " from real_divergence where bot_wallet_id = :w and state in"
                        + " ('OPEN', 'COMPENSATED')")
                .param("w", botWalletId)
                .query(JdbcRealOpsStore::divergence)
                .optional();
    }

    @Override
    public Optional<DivergenceRow> lockDivergence(UUID divergenceId) {
        jdbc.sql("set local lock_timeout = '2s'").update();
        return jdbc.sql("select " + DIVERGENCE_COLUMNS + " from real_divergence where divergence_id = :id for update")
                .param("id", divergenceId)
                .query(JdbcRealOpsStore::divergence)
                .optional();
    }

    @Override
    public void insertDivergence(DivergenceRow divergence) {
        jdbc.sql("""
                        insert into real_divergence (divergence_id, run_id, last_run_id, tenant_id, bot_wallet_id,
                          lamports_diff, token_diffs, state, opened_at, updated_at)
                        values (:id, :run, :last, :t, :w, :diff, cast(:diffs as jsonb), :state, :opened, :opened)""")
                .param("id", divergence.divergenceId())
                .param("run", divergence.runId())
                .param("last", divergence.lastRunId())
                .param("t", divergence.tenantId())
                .param("w", divergence.botWalletId())
                .param("diff", divergence.lamportsDiff())
                .param("diffs", TokenDiffsJson.write(divergence.tokenDiffs()))
                .param("state", divergence.state())
                .param("opened", Timestamp.from(divergence.openedAt()))
                .update();
    }

    @Override
    public boolean remeasure(UUID divergenceId, String expectedState, RunRecord run) {
        return jdbc.sql("""
                                update real_divergence set state = 'OPEN', lamports_diff = :diff,
                                  token_diffs = cast(:diffs as jsonb), last_run_id = :run, updated_at = :at
                                where divergence_id = :id and state = :expected""")
                        .param("diff", run.outcome().lamportsDiff())
                        .param("diffs", TokenDiffsJson.write(run.outcome().tokenDiffs()))
                        .param("run", run.runId())
                        .param("at", Timestamp.from(run.finishedAt()))
                        .param("id", divergenceId)
                        .param("expected", expectedState)
                        .update()
                == 1;
    }

    @Override
    public boolean compensate(
            UUID divergenceId, List<String> entryReferences, UUID adminId, String reason, Instant at) {
        return jdbc.sql("""
                                update real_divergence set state = 'COMPENSATED',
                                  compensation_entry_ids = cast(:refs as jsonb), compensated_by = :admin,
                                  compensated_at = :at, reason = :reason, updated_at = :at
                                where divergence_id = :id and state = 'OPEN'""")
                        .param("refs", TokenDiffsJson.writeStrings(entryReferences))
                        .param("admin", adminId)
                        .param("at", Timestamp.from(at))
                        .param("reason", reason)
                        .param("id", divergenceId)
                        .update()
                == 1;
    }

    @Override
    public boolean resolve(UUID divergenceId, UUID runId, Instant at) {
        return jdbc.sql("""
                                update real_divergence set state = 'RESOLVED', last_run_id = :run, resolved_at = :at,
                                  updated_at = :at
                                where divergence_id = :id and state = 'COMPENSATED'""")
                        .param("run", runId)
                        .param("at", Timestamp.from(at))
                        .param("id", divergenceId)
                        .update()
                == 1;
    }

    @Override
    public Optional<Long> lastChainLamports(UUID botWalletId) {
        return jdbc.sql("select chain_lamports from real_reconciliation_run where bot_wallet_id = :w and result in"
                        + " ('CLEAN', 'DIVERGENT') order by started_at desc limit 1")
                .param("w", botWalletId)
                .query(Long.class)
                .optional();
    }

    @Override
    public List<Candidate> candidates() {
        return jdbc.sql("""
                        select a.tenant_id, a.user_id, a.bot_wallet_id,
                          (select max(r.started_at) from real_reconciliation_run r
                            where r.bot_wallet_id = a.bot_wallet_id) as last_run,
                          (select max(r.started_at) from real_reconciliation_run r
                            where r.bot_wallet_id = a.bot_wallet_id and r.trigger = 'AFTER_RESTORE') as last_restore_run,
                          (select max(d.compensated_at) from real_divergence d
                            where d.bot_wallet_id = a.bot_wallet_id and d.state = 'COMPENSATED') as compensated_at,
                          (select max(m.created_at) from reconciliation_mark m
                            where m.bot_wallet_id = a.bot_wallet_id) as last_mark
                        from bot_wallet_account a where a.status = 'ACTIVE' order by a.bot_wallet_id""")
                .query((rs, row) -> new Candidate(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("bot_wallet_id", UUID.class),
                        JdbcBotWalletRepository.instant(rs, "last_run"),
                        JdbcBotWalletRepository.instant(rs, "last_restore_run"),
                        JdbcBotWalletRepository.instant(rs, "compensated_at"),
                        JdbcBotWalletRepository.instant(rs, "last_mark")))
                .list();
    }

    @Override
    public List<DivergenceRow> openDivergences(Optional<Instant> beforeOpenedAt, Optional<UUID> beforeId, int limit) {
        String after = beforeOpenedAt.isPresent() && beforeId.isPresent()
                ? " and (opened_at, divergence_id) < (:at, :id)"
                : "";
        var statement = jdbc.sql("select " + DIVERGENCE_COLUMNS + " from real_divergence where state in ('OPEN',"
                        + " 'COMPENSATED')" + after + " order by opened_at desc, divergence_id desc limit :limit")
                .param("limit", limit);
        if (!after.isEmpty()) {
            statement =
                    statement.param("at", Timestamp.from(beforeOpenedAt.get())).param("id", beforeId.get());
        }
        return statement.query(JdbcRealOpsStore::divergence).list();
    }

    // ----- the restore check (BR2.5) -----

    @Override
    public boolean openCheckPending(Instant at) {
        return jdbc.sql("insert into real_restore_marker (marker_id, phase, opened_at) select :id, 'CHECK_PENDING', :at"
                                + " where not exists (select 1 from real_restore_marker where cleared_at is null)"
                                + " on conflict do nothing")
                        .param("id", UUID.randomUUID())
                        .param("at", Timestamp.from(at))
                        .update()
                == 1;
    }

    @Override
    public Optional<Marker> openMarker() {
        return jdbc.sql("select marker_id, phase, opened_at, detected_at from real_restore_marker where cleared_at is"
                        + " null")
                .query((rs, row) -> new Marker(
                        rs.getObject("marker_id", UUID.class),
                        rs.getString("phase"),
                        JdbcBotWalletRepository.instant(rs, "opened_at"),
                        JdbcBotWalletRepository.instant(rs, "detected_at")))
                .optional();
    }

    @Override
    public List<WalletToCheck> walletsToCheck() {
        return jdbc.sql("select bot_wallet_id, public_address, deposit_cursor from bot_wallet_account where"
                        + " public_address is not null order by created_at")
                .query((rs, row) -> new WalletToCheck(
                        rs.getObject("bot_wallet_id", UUID.class),
                        rs.getString("public_address"),
                        rs.getString("deposit_cursor")))
                .list();
    }

    @Override
    public java.util.Set<String> knownSignatures(UUID botWalletId, java.util.Collection<String> candidates) {
        if (candidates.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.sql("select tx_signature from withdrawal where bot_wallet_id = :w and"
                        + " tx_signature in (:sigs) union select tx_signature from observed_deposit where bot_wallet_id"
                        + " = :w and tx_signature in (:sigs)")
                .param("w", botWalletId)
                .param("sigs", List.copyOf(candidates))
                .query(String.class)
                .list());
    }

    @Override
    public boolean toRestore(UUID markerId, int unknownSignatures, Instant at) {
        return jdbc.sql("update real_restore_marker set phase = 'RESTORE', detected_at = :at, unknown_signatures ="
                                + " :unknown where marker_id = :id and phase = 'CHECK_PENDING' and cleared_at is null")
                        .param("at", Timestamp.from(at))
                        .param("unknown", unknownSignatures)
                        .param("id", markerId)
                        .update()
                == 1;
    }

    @Override
    public boolean clear(UUID markerId, UUID confirmedBy, Instant at) {
        return jdbc.sql("update real_restore_marker set cleared_at = :at, confirmed_by = :by where marker_id = :id and"
                                + " cleared_at is null")
                        .param("at", Timestamp.from(at))
                        .param("by", confirmedBy)
                        .param("id", markerId)
                        .update()
                == 1;
    }

    @Override
    public boolean everyWalletCleanAfter(Instant detectedAt) {
        return jdbc.sql("""
                        select not exists (
                          select 1 from bot_wallet_account a where a.status = 'ACTIVE' and coalesce((
                            select r.result from real_reconciliation_run r
                            where r.bot_wallet_id = a.bot_wallet_id and r.trigger = 'AFTER_RESTORE'
                              and r.started_at >= :at
                            order by r.started_at desc limit 1), 'NONE') <> 'CLEAN')""")
                .param("at", Timestamp.from(detectedAt))
                .query(Boolean.class)
                .single();
    }

    // ----- the commands to the TxSigner (BR4.1, BR6.2) -----

    @Override
    public void insert(PendingCommand command) {
        jdbc.sql("""
                        insert into real_pending_txsigner_command (command_id, bot_wallet_id, kind, payload, attempts,
                          created_at)
                        values (:id, :w, :kind, cast(:payload as jsonb), 0, :at)""")
                .param("id", command.commandId())
                .param("w", command.botWalletId())
                .param("kind", command.kind().name())
                .param("payload", TokenDiffsJson.writeMap(command.payload()))
                .param("at", Timestamp.from(command.createdAt()))
                .update();
    }

    @Override
    public List<PendingCommand> pending(int limit) {
        return jdbc.sql("select command_id, bot_wallet_id, kind, payload::text as payload, attempts, created_at from"
                        + " real_pending_txsigner_command where confirmed_at is null order by created_at, command_id"
                        + " limit :limit")
                .param("limit", limit)
                .query((rs, row) -> new PendingCommand(
                        rs.getObject("command_id", UUID.class),
                        rs.getObject("bot_wallet_id", UUID.class),
                        CommandStore.Kind.valueOf(rs.getString("kind")),
                        TokenDiffsJson.readMap(rs.getString("payload")),
                        rs.getInt("attempts"),
                        JdbcBotWalletRepository.instant(rs, "created_at")))
                .list();
    }

    @Override
    public Optional<String> botAddress(UUID botWalletId) {
        return jdbc.sql("select public_address from bot_wallet_account where bot_wallet_id = :w and public_address is"
                        + " not null")
                .param("w", botWalletId)
                .query(String.class)
                .optional();
    }

    @Override
    public void attempted(UUID commandId, Instant at) {
        jdbc.sql("update real_pending_txsigner_command set attempts = attempts + 1, last_attempt_at = :at where"
                        + " command_id = :id and confirmed_at is null")
                .param("at", Timestamp.from(at))
                .param("id", commandId)
                .update();
    }

    @Override
    public boolean confirmed(UUID commandId, Instant at) {
        return jdbc.sql("update real_pending_txsigner_command set confirmed_at = :at where command_id = :id and"
                                + " confirmed_at is null")
                        .param("at", Timestamp.from(at))
                        .param("id", commandId)
                        .update()
                == 1;
    }

    @Override
    public void clearBotAddress(UUID botWalletId) {
        jdbc.sql("update bot_wallet_account set public_address = null where bot_wallet_id = :w and status = 'CLOSED'")
                .param("w", botWalletId)
                .update();
    }

    // ----- the closing by an anonymization (BR6.1, BR6.2, U5-NFR1.3) -----

    @Override
    public Optional<WalletToClose> lockActiveWallet(UUID tenantId, UUID userId) {
        return jdbc.sql("select bot_wallet_id, public_address from bot_wallet_account where tenant_id = :t and user_id"
                        + " = :u and status = 'ACTIVE' for update")
                .param("t", tenantId)
                .param("u", userId)
                .query((rs, row) ->
                        new WalletToClose(rs.getObject("bot_wallet_id", UUID.class), rs.getString("public_address")))
                .optional();
    }

    @Override
    public int withdrawalsInProgress(UUID botWalletId) {
        return jdbc.sql("select count(*) from withdrawal where bot_wallet_id = :w and state in ('REQUESTED', 'SIGNED',"
                        + " 'SENT', 'AMBIGUOUS')")
                .param("w", botWalletId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void closeWallet(UUID botWalletId, Instant at) {
        jdbc.sql("update bot_wallet_account set status = 'CLOSED', closed_at = :at, main_wallet_address = null where"
                        + " bot_wallet_id = :w and status = 'ACTIVE'")
                .param("at", Timestamp.from(at))
                .param("w", botWalletId)
                .update();
    }

    @Override
    public int clearPersonalData(UUID tenantId, UUID userId, Instant at) {
        int wallets = jdbc.sql("update bot_wallet_account set main_wallet_address = null where tenant_id = :t and"
                        + " user_id = :u and status = 'CLOSED' and main_wallet_address is not null")
                .param("t", tenantId)
                .param("u", userId)
                .update();
        int challenges = jdbc.sql("update consent_challenge set message = null where tenant_id = :t and user_id = :u"
                        + " and message is not null")
                .param("t", tenantId)
                .param("u", userId)
                .update();
        int withdrawals = jdbc.sql("""
                        update withdrawal set signed_transaction = null, message_digest = null, redacted_at = :at
                        where tenant_id = :t and redacted_at is null
                          and bot_wallet_id in (select bot_wallet_id from bot_wallet_account
                                                where tenant_id = :t and user_id = :u)""")
                .param("at", Timestamp.from(at))
                .param("t", tenantId)
                .param("u", userId)
                .update();
        return wallets + challenges + withdrawals;
    }

    @Override
    public int redactAudit(String botWalletAddress) {
        return jdbc.sql("select redact_real_wallet_audit(:address)")
                .param("address", botWalletAddress)
                .query(Integer.class)
                .single();
    }

    // ----- the view of the administrator (BR5.1) -----

    private static final String WALLET_ROW_COLUMNS =
            "tenant_id, user_id, bot_wallet_id, status, public_address, main_wallet_address, created_at";

    @Override
    public List<WalletRow> wallets(Optional<Instant> afterCreatedAt, Optional<UUID> afterId, int limit) {
        String after = afterCreatedAt.isPresent() && afterId.isPresent()
                ? " where (created_at, bot_wallet_id) > (:at, :id)"
                : "";
        var statement = jdbc.sql("select " + WALLET_ROW_COLUMNS + " from bot_wallet_account" + after
                        + " order by created_at, bot_wallet_id limit :limit")
                .param("limit", limit);
        if (!after.isEmpty()) {
            statement =
                    statement.param("at", Timestamp.from(afterCreatedAt.get())).param("id", afterId.get());
        }
        return statement.query(JdbcRealOpsStore::walletRow).list();
    }

    @Override
    public Optional<WalletRow> walletOf(UUID userId) {
        return jdbc.sql("select " + WALLET_ROW_COLUMNS + " from bot_wallet_account where user_id = :u order by"
                        + " (status = 'ACTIVE') desc, created_at desc limit 1")
                .param("u", userId)
                .query(JdbcRealOpsStore::walletRow)
                .optional();
    }

    private static WalletRow walletRow(ResultSet rs, int row) throws SQLException {
        return new WalletRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("bot_wallet_id", UUID.class),
                rs.getString("status"),
                rs.getString("public_address"),
                rs.getString("main_wallet_address"),
                JdbcBotWalletRepository.instant(rs, "created_at"));
    }

    static DivergenceRow divergence(ResultSet rs, int row) throws SQLException {
        return new DivergenceRow(
                rs.getObject("divergence_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("last_run_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("bot_wallet_id", UUID.class),
                rs.getLong("lamports_diff"),
                TokenDiffsJson.read(rs.getString("token_diffs")),
                rs.getString("state"),
                TokenDiffsJson.readStrings(rs.getString("compensation_entry_ids")),
                JdbcBotWalletRepository.instant(rs, "compensated_at"),
                JdbcBotWalletRepository.instant(rs, "opened_at"));
    }

    static RealRelease release(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("blocked_reason");
        return new RealRelease(
                rs.getObject("release_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Network.valueOf(rs.getString("network")),
                rs.getObject("requested_by", UUID.class),
                JdbcBotWalletRepository.instant(rs, "requested_at"),
                JdbcBotWalletRepository.instant(rs, "effective_at"),
                JdbcBotWalletRepository.instant(rs, "revoked_at"),
                ReleaseState.valueOf(rs.getString("state")),
                reason == null ? null : BlockedReason.valueOf(reason),
                JdbcBotWalletRepository.instant(rs, "gates_checked_at"),
                rs.getObject("revoked_by", UUID.class));
    }
}
