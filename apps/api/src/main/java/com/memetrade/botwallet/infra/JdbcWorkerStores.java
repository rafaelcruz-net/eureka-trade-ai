package com.memetrade.botwallet.infra;

import com.memetrade.audit.api.RealReferences;
import com.memetrade.botwallet.domain.DepositStore;
import com.memetrade.botwallet.domain.Withdrawal;
import com.memetrade.botwallet.domain.WithdrawalStore;
import com.memetrade.botwallet.domain.WithdrawalTransition;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.SlotWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional stores of the worker (BR3.1, BR3.2, BR4.7, BR6.1, BR6.4): a deposit, its ledger entry, its audit event
 * and, for an unknown source, a reconciliation mark commit together; a withdrawal step, its ledger entries and its
 * audit event commit together. The tenant written on each wallet is the one used for every entry. The source of a
 * deposit is stored only as an HMAC with the key of the audit (never the address).
 */
@Component
public class JdbcWorkerStores implements DepositStore, WithdrawalStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RealLedger ledger;
    private final RealAudit audit;
    private final Clock clock;
    private final byte[] hmacKey;
    private final RealMetrics metrics;

    public JdbcWorkerStores(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RealLedger ledger,
            RealAudit audit,
            Clock clock,
            @Value("${memetrade.audit.wallet-hmac-key}") String hmacKey,
            RealMetrics metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
        this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<WalletToScan> activeWallets() {
        return jdbc.sql("select bot_wallet_id, tenant_id, public_address, main_wallet_address, deposit_cursor "
                        + "from bot_wallet_account where status = 'ACTIVE' order by created_at")
                .query((rs, row) -> new WalletToScan(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("bot_wallet_id", UUID.class),
                        rs.getString("public_address"),
                        rs.getString("main_wallet_address"),
                        rs.getString("deposit_cursor")))
                .list();
    }

    @Override
    public boolean record(WalletToScan wallet, RecordedDeposit deposit) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            String signature = deposit.transfer().txSignature();
            int inserted = jdbc.sql("""
                            insert into observed_deposit (tx_signature, tenant_id, bot_wallet_id, source_address_hmac,
                              lamports, finalized_at, known_source)
                            values (:sig, :tenant, :wallet, :source, :lamports, :at, :known)
                            on conflict do nothing""")
                    .param("sig", signature)
                    .param("tenant", wallet.tenantId())
                    .param("wallet", wallet.botWalletId())
                    .param("source", hmac(deposit.transfer().sourceAddress()))
                    .param("lamports", deposit.transfer().lamports())
                    .param("at", Timestamp.from(deposit.transfer().finalizedAt()))
                    .param("known", deposit.knownSource())
                    .update();
            if (inserted == 0) {
                return false;
            }
            ledger.record(new RealEntry(
                    wallet.tenantId(),
                    wallet.botWalletId(),
                    RealEntryKind.DEPOSIT,
                    deposit.transfer().lamports(),
                    signature,
                    "dep:" + signature,
                    "dep:" + signature));
            deposit.transfer()
                    .slot()
                    .ifPresent(slot ->
                            ledger.recordSlot(wallet.tenantId(), signature, slot, SlotWriter.WALLET_CONFIRMATION));
            // 261121-paper-real-unico U2a, Etapa 14b: the marks of zero of the wallet, after the deposit and its slot.
            ledger.markEpochs(wallet.tenantId(), wallet.botWalletId());
            RealReferences real = RealReferences.transaction(signature, wallet.botWalletAddress());
            audit.system(
                    wallet.tenantId(),
                    "REAL_DEPOSIT_RECORDED",
                    "bot-wallet:" + wallet.botWalletId(),
                    Map.of(
                            "lamports",
                            Long.toString(deposit.transfer().lamports()),
                            "knownSource",
                            Boolean.toString(deposit.knownSource()),
                            "notify",
                            Boolean.toString(deposit.notifyPerson())),
                    real);
            metrics.deposit(deposit.knownSource(), deposit.transfer().lamports());
            if (!deposit.knownSource()) {
                mark(wallet.botWalletId(), "DEPOSIT_UNKNOWN_SOURCE");
                audit.system(
                        wallet.tenantId(),
                        "REAL_DEPOSIT_UNKNOWN_SOURCE",
                        "bot-wallet:" + wallet.botWalletId(),
                        Map.of(),
                        real);
            }
            return true;
        }));
    }

    @Override
    public void advanceCursor(WalletToScan wallet, String lastSignature) {
        jdbc.sql("update bot_wallet_account set deposit_cursor = :cursor where bot_wallet_id = :id and tenant_id = :t")
                .param("cursor", lastSignature)
                .param("id", wallet.botWalletId())
                .param("t", wallet.tenantId())
                .update();
    }

    @Override
    public List<Withdrawal> claimReady(Instant now, Duration lease, int limit) {
        return transactions.execute(status -> jdbc
                .sql("update withdrawal set next_attempt_at = :leased "
                        + "where withdrawal_id in (select withdrawal_id from withdrawal "
                        + "where state in ('REQUESTED', 'SIGNED', 'SENT', 'AMBIGUOUS') and next_attempt_at <= :now "
                        + "order by next_attempt_at limit :limit for update skip locked) "
                        + "returning " + JdbcBotWalletRepository.WITHDRAWAL_COLUMNS)
                .param("leased", Timestamp.from(now.plus(lease)))
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(JdbcBotWalletRepository::withdrawal)
                .list()
                .stream()
                .map(w -> w.pollAgainAt(now))
                .toList());
    }

    @Override
    public Optional<WithdrawalTarget> target(Withdrawal withdrawal) {
        return jdbc.sql("select public_address, main_wallet_address from bot_wallet_account "
                        + "where bot_wallet_id = :id and tenant_id = :tenant")
                .param("id", withdrawal.botWalletId())
                .param("tenant", withdrawal.tenantId())
                .query((rs, row) ->
                        new WithdrawalTarget(rs.getString("public_address"), rs.getString("main_wallet_address")))
                .optional();
    }

    @Override
    public void save(WithdrawalTransition transition) {
        Withdrawal w = transition.withdrawal();
        transactions.executeWithoutResult(status -> {
            String previous = jdbc.sql("select state from withdrawal where withdrawal_id = :id for update")
                    .param("id", w.withdrawalId())
                    .query(String.class)
                    .single();
            jdbc.sql("""
                            update withdrawal set state = :state, blockhash = :hash, last_valid_height = :height,
                              signed_transaction = :signed, message_digest = :digest, tx_signature = :sig,
                              failure_code = :failure, attempts = :attempts, next_attempt_at = :next, updated_at = :at
                            where withdrawal_id = :id and tenant_id = :tenant""")
                    .param("state", w.state().name())
                    .param("hash", w.blockhash())
                    .param("height", w.lastValidHeight())
                    .param("signed", w.signedTransaction())
                    .param("digest", w.messageDigest())
                    .param("sig", w.txSignature())
                    .param("failure", w.failureCode())
                    .param("attempts", w.attempts())
                    .param("next", Timestamp.from(w.nextAttemptAt()))
                    .param("at", Timestamp.from(w.updatedAt()))
                    .param("id", w.withdrawalId())
                    .param("tenant", w.tenantId())
                    .update();
            transition.entries().forEach(ledger::record);
            if (w.txSignature() != null) {
                // 261121-paper-real-unico U2 (BR1.4): the slot of the confirmed withdrawal and of its fee.
                transition
                        .slot()
                        .ifPresent(slot ->
                                ledger.recordSlot(w.tenantId(), w.txSignature(), slot, SlotWriter.WALLET_CONFIRMATION));
            }
            if (!transition.entries().isEmpty()
                    || (w.txSignature() != null && transition.slot().isPresent())) {
                // 261121-paper-real-unico U2a, Etapa 14b: the marks of zero, after the entries and the slot.
                ledger.markEpochs(w.tenantId(), w.botWalletId());
            }
            if (!previous.equals(w.state().name())) {
                metrics.withdrawalState(w.state().name(), w.requestedAt());
                audit.system(
                        w.tenantId(),
                        "REAL_WITHDRAWAL_" + w.state().name(),
                        "withdrawal:" + w.withdrawalId(),
                        w.failureCode() == null ? Map.of() : Map.of("failureCode", w.failureCode()),
                        w.txSignature() == null ? null : new RealReferences(w.txSignature(), null, null));
                if ("CONFIRMED".equals(w.state().name())) {
                    mark(w.botWalletId(), "WITHDRAWAL_CONFIRMED");
                }
            }
        });
    }

    private void mark(UUID botWalletId, String reason) {
        metrics.reconciliationMark();
        jdbc.sql("insert into reconciliation_mark (mark_id, bot_wallet_id, reason, created_at) "
                        + "values (:id, :wallet, :reason, :at)")
                .param("id", UUID.randomUUID())
                .param("wallet", botWalletId)
                .param("reason", reason)
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    private String hmac(String address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", failure);
        }
    }
}
