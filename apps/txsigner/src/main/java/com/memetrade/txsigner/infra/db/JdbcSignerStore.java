package com.memetrade.txsigner.infra.db;

import com.memetrade.txsigner.domain.OperationKind;
import com.memetrade.txsigner.domain.PolicyChange;
import com.memetrade.txsigner.domain.SignerStore;
import com.memetrade.txsigner.domain.SigningRecord;
import com.memetrade.txsigner.domain.WalletPolicy;
import com.memetrade.txsigner.policy.OperationMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC adapter of {@link SignerStore} over the schema {@code txsigner}. The writes that change a policy run in one
 * transaction with their nonce: {@code ON CONFLICT DO NOTHING} detects a repeated request or a used nonce, and the
 * transaction is rolled back so nothing partial remains (BR1.8, BR1.10).
 */
public class JdbcSignerStore implements SignerStore {

    private static final String POLICY_COLUMNS = "bot_wallet_address, kms_key_ref, main_wallet_address, "
            + "authorized_cap_lamports, authorized_mode, closed, blocked, restored, creation_request_id, "
            + "max_withdrawal_fee_lamports, created_at, updated_at";
    private static final String RECORD_COLUMNS = "signing_request_id, bot_wallet_address, operation_kind, "
            + "message_digest, decision, refusal_code, signature, signed_transaction, decided_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcSignerStore(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<WalletPolicy> findPolicy(String botWalletAddress) {
        return jdbc.sql("SELECT " + POLICY_COLUMNS + " FROM wallet_policy WHERE bot_wallet_address = :address")
                .param("address", botWalletAddress)
                .query(JdbcSignerStore::policy)
                .optional();
    }

    @Override
    public Optional<WalletPolicy> findPolicyByCreationRequest(UUID creationRequestId) {
        return jdbc.sql("SELECT " + POLICY_COLUMNS + " FROM wallet_policy WHERE creation_request_id = :id")
                .param("id", creationRequestId)
                .query(JdbcSignerStore::policy)
                .optional();
    }

    @Override
    public int countPoliciesCreatedSince(Instant since) {
        return jdbc.sql("SELECT count(*) FROM wallet_policy WHERE created_at >= :since AND NOT restored")
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    @Override
    public List<WalletPolicy> listPoliciesCreatedBefore(Instant before, int limit) {
        return jdbc.sql("SELECT " + POLICY_COLUMNS
                        + " FROM wallet_policy WHERE created_at < :before ORDER BY created_at LIMIT :limit")
                .param("before", Timestamp.from(before))
                .param("limit", limit)
                .query(JdbcSignerStore::policy)
                .list();
    }

    @Override
    public boolean isNonceUsed(String nonce) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM signer_used_nonce WHERE nonce = :nonce)")
                .param("nonce", nonce)
                .query(Boolean.class)
                .single();
    }

    @Override
    public WriteResult insertPolicy(WalletPolicy policy, String nonce, Instant noncePurgeAfter) {
        return transactions.execute(status -> {
            if (insertPolicyRow(policy) == 0) {
                status.setRollbackOnly();
                return WriteResult.DUPLICATE_REQUEST;
            }
            if (insertNonce(nonce, noncePurgeAfter) == 0) {
                status.setRollbackOnly();
                return WriteResult.NONCE_USED;
            }
            return WriteResult.APPLIED;
        });
    }

    @Override
    public WriteResult insertRestoredPolicy(WalletPolicy policy) {
        return insertPolicyRow(policy) == 0 ? WriteResult.DUPLICATE_REQUEST : WriteResult.APPLIED;
    }

    @Override
    public WriteResult applyChange(PolicyChange change, WalletPolicy updated, String nonce, Instant noncePurgeAfter) {
        return transactions.execute(status -> {
            int inserted = jdbc.sql("INSERT INTO policy_change (request_id, bot_wallet_address, kind, cap_lamports, "
                            + "mode, consent_digest, decided_at) VALUES (:id, :address, :kind, :cap, :mode, :digest, "
                            + ":at) ON CONFLICT DO NOTHING")
                    .param("id", change.requestId())
                    .param("address", change.botWalletAddress())
                    .param("kind", change.kind().name())
                    .param("cap", change.capLamports())
                    .param("mode", change.mode().name())
                    .param("digest", change.consentDigest())
                    .param("at", Timestamp.from(change.decidedAt()))
                    .update();
            if (inserted == 0) {
                status.setRollbackOnly();
                return WriteResult.DUPLICATE_REQUEST;
            }
            if (nonce != null && insertNonce(nonce, noncePurgeAfter) == 0) {
                status.setRollbackOnly();
                return WriteResult.NONCE_USED;
            }
            updatePolicy(updated);
            return WriteResult.APPLIED;
        });
    }

    @Override
    public void startRecoveryRun(UUID runId, Instant startedAt) {
        jdbc.sql("INSERT INTO recovery_run (run_id, started_at) VALUES (:id, :at)")
                .param("id", runId)
                .param("at", Timestamp.from(startedAt))
                .update();
    }

    @Override
    public void completeRecoveryRun(UUID runId, Instant completedAt, int restored, int blocked) {
        jdbc.sql("UPDATE recovery_run SET recovery_completed_at = :at, restored_count = :restored, "
                        + "blocked_count = :blocked WHERE run_id = :id")
                .param("at", Timestamp.from(completedAt))
                .param("restored", restored)
                .param("blocked", blocked)
                .param("id", runId)
                .update();
    }

    @Override
    public void updatePolicy(WalletPolicy policy) {
        jdbc.sql("UPDATE wallet_policy SET authorized_cap_lamports = :cap, authorized_mode = :mode, closed = :closed, "
                        + "blocked = :blocked, updated_at = :at WHERE bot_wallet_address = :address")
                .param("cap", policy.authorizedCapLamports())
                .param("mode", policy.authorizedMode().name())
                .param("closed", policy.closed())
                .param("blocked", policy.blocked())
                .param("at", Timestamp.from(policy.updatedAt()))
                .param("address", policy.botWalletAddress())
                .update();
    }

    @Override
    public Optional<PolicyChange> findChange(UUID requestId) {
        return jdbc.sql("SELECT request_id, bot_wallet_address, kind, cap_lamports, mode, consent_digest, decided_at "
                        + "FROM policy_change WHERE request_id = :id")
                .param("id", requestId)
                .query((rs, row) -> new PolicyChange(
                        rs.getObject("request_id", UUID.class),
                        rs.getString("bot_wallet_address"),
                        PolicyChange.Kind.valueOf(rs.getString("kind")),
                        rs.getLong("cap_lamports"),
                        OperationMode.valueOf(rs.getString("mode")),
                        rs.getString("consent_digest"),
                        instant(rs, "decided_at")))
                .optional();
    }

    @Override
    public Optional<SigningRecord> findSigningRecord(UUID signingRequestId) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM signing_record WHERE signing_request_id = :id")
                .param("id", signingRequestId)
                .query(JdbcSignerStore::record)
                .optional();
    }

    @Override
    public boolean insertSigningRecord(SigningRecord record) {
        return jdbc.sql("INSERT INTO signing_record (" + RECORD_COLUMNS + ") VALUES (:id, :address, :kind, :digest, "
                                + ":decision, :refusal, :signature, :signed, :at) ON CONFLICT DO NOTHING")
                        .param("id", record.signingRequestId())
                        .param("address", record.botWalletAddress())
                        .param("kind", record.operationKind().name())
                        .param("digest", record.messageDigest())
                        .param("decision", record.decision().name())
                        .param("refusal", record.refusalCode())
                        .param("signature", record.signature())
                        .param("signed", record.signedTransactionBase64())
                        .param("at", Timestamp.from(record.decidedAt()))
                        .update()
                == 1;
    }

    @Override
    public List<SigningRecord> listSignedSince(Instant since, int limit) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM signing_record WHERE decision = 'SIGNED' "
                        + "AND decided_at >= :since ORDER BY decided_at, signing_request_id LIMIT :limit")
                .param("since", Timestamp.from(since))
                .param("limit", limit)
                .query(JdbcSignerStore::record)
                .list();
    }

    @Override
    public int purgeNonces(Instant now, int batchSize) {
        return jdbc.sql("DELETE FROM signer_used_nonce WHERE nonce IN (SELECT nonce FROM signer_used_nonce "
                        + "WHERE purge_after < :now ORDER BY purge_after LIMIT :batch)")
                .param("now", Timestamp.from(now))
                .param("batch", batchSize)
                .update();
    }

    private int insertPolicyRow(WalletPolicy policy) {
        return jdbc.sql("INSERT INTO wallet_policy (" + POLICY_COLUMNS + ") VALUES (:address, :keyRef, :main, :cap, "
                        + ":mode, :closed, :blocked, :restored, :requestId, :maxFee, :createdAt, :updatedAt) "
                        + "ON CONFLICT DO NOTHING")
                .param("address", policy.botWalletAddress())
                .param("keyRef", policy.kmsKeyRef())
                .param("main", policy.mainWalletAddress())
                .param("cap", policy.authorizedCapLamports())
                .param("mode", policy.authorizedMode().name())
                .param("closed", policy.closed())
                .param("blocked", policy.blocked())
                .param("restored", policy.restored())
                .param("requestId", policy.creationRequestId())
                .param("maxFee", policy.maxWithdrawalFeeLamports())
                .param("createdAt", Timestamp.from(policy.createdAt()))
                .param("updatedAt", Timestamp.from(policy.updatedAt()))
                .update();
    }

    private int insertNonce(String nonce, Instant purgeAfter) {
        return jdbc.sql("INSERT INTO signer_used_nonce (nonce, purge_after) VALUES (:nonce, :purge) "
                        + "ON CONFLICT DO NOTHING")
                .param("nonce", nonce)
                .param("purge", Timestamp.from(purgeAfter))
                .update();
    }

    private static WalletPolicy policy(ResultSet rs, int row) throws SQLException {
        return new WalletPolicy(
                rs.getString("bot_wallet_address"),
                rs.getString("kms_key_ref"),
                rs.getString("main_wallet_address"),
                rs.getLong("authorized_cap_lamports"),
                OperationMode.valueOf(rs.getString("authorized_mode")),
                rs.getBoolean("closed"),
                rs.getBoolean("blocked"),
                rs.getBoolean("restored"),
                rs.getObject("creation_request_id", UUID.class),
                rs.getLong("max_withdrawal_fee_lamports"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static SigningRecord record(ResultSet rs, int row) throws SQLException {
        return new SigningRecord(
                rs.getObject("signing_request_id", UUID.class),
                rs.getString("bot_wallet_address"),
                OperationKind.valueOf(rs.getString("operation_kind")),
                rs.getString("message_digest"),
                SigningRecord.Decision.valueOf(rs.getString("decision")),
                rs.getString("refusal_code"),
                rs.getString("signature"),
                rs.getString("signed_transaction"),
                instant(rs, "decided_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
