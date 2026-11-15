package com.memetrade.riskcontrol.real.infra;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.riskcontrol.api.BlockedToken;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.HandOverResult;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.riskcontrol.api.UnblockResult;
import com.memetrade.riskcontrol.real.domain.BuyInputs;
import com.memetrade.riskcontrol.real.domain.RealGateInputs;
import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import com.memetrade.riskcontrol.real.domain.RealRiskPolicy;
import com.memetrade.riskcontrol.real.domain.RealVerdict;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.logging.LogMasking;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 261113-trading-real-bot U3, the bean of M5 (created by {@link RealRiskConfig}): the pure {@link RealRiskPolicy} inside
 * the transaction of the caller ({@code MANDATORY}, BR6.1, BR7.1, U3-NFR4.6).
 *
 * <p>{@code evaluate} of a BUY (F1): takes the lock of the wallet (BR6.1); returns the recorded decision when the
 * decision was already evaluated, before any read (U3-NFR4.2); reads the state and the source and stops at their
 * refusal; reads the inputs of the buy; runs the policy; records the evaluation, the reservation, the photo of the day,
 * the audit event of a refusal and the DAILY_LOSS pause, all in the transaction of the caller. A SELL (F2) takes no
 * lock and is recorded with {@code ON CONFLICT (decision_id)}: a concurrent repetition returns the recorded answer.
 * A technical failure is counted and thrown ({@link RealGateUnavailable}): it never approves (U3-NFR4.1).
 *
 * <p>{@link RealGateUnavailable} is thrown only before anything is written. After a LOCK timeout the transaction of the
 * caller is still usable and is not marked rollback-only (U3-NFR4.8): the caller (the U4) decides. After a SQL failure
 * in a read ({@link RealGateUnavailable#transactionUsable()} false) PostgreSQL already aborted the transaction: it is
 * marked rollback-only and the caller has to roll back (review R-02). Any other failure marks it too.
 *
 * <p>The block list opens its own transaction ({@code REQUIRED}): the route of the administrator has no cycle.
 */
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RealGateUnavailable.class)
public class TransactionalRealRiskGate implements RealRiskGate {

    private static final Logger log = LoggerFactory.getLogger(TransactionalRealRiskGate.class);
    private static final Pattern BASE58_ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private final WalletLock lock;
    private final RealGateInputsReader reader;
    private final JdbcRealRiskStore store;
    private final RealRiskPolicy policy;
    private final RealRiskLimits limits;
    private final RealPauses pauses;
    private final RealRiskAudit audit;
    private final RealRiskMetrics metrics;
    private final Clock clock;

    public TransactionalRealRiskGate(
            WalletLock lock,
            RealGateInputsReader reader,
            JdbcRealRiskStore store,
            RealRiskPolicy policy,
            RealRiskLimits limits,
            RealPauses pauses,
            RealRiskAudit audit,
            RealRiskMetrics metrics,
            Clock clock) {
        this.lock = lock;
        this.reader = reader;
        this.store = store;
        this.policy = policy;
        this.limits = limits;
        this.pauses = pauses;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public GateDecision evaluate(OrderIntent intent) {
        try {
            if (intent.isBuy()) {
                metrics.lockWaited(lock.acquire(intent.userId()));
            }
            long started = System.nanoTime();
            if (intent.decisionId() != null) {
                Optional<GateDecision> recorded = store.decisionOf(intent.tenantId(), intent.decisionId());
                if (recorded.isPresent()) {
                    return recorded.get();
                }
            }
            Instant now = clock.instant();
            Optional<RealStateView> wallet = reader.wallet(intent.tenantId(), intent.userId());
            SourceKind source = reader.source();
            Optional<RealVerdict> refused = policy.stateAndSource(intent, wallet, source);
            RealVerdict verdict;
            if (refused.isPresent()) {
                verdict = refused.get();
            } else {
                Optional<BuyInputs> buy =
                        intent.isBuy() ? Optional.of(reader.buy(intent, wallet.get(), now)) : Optional.empty();
                verdict = policy.evaluate(new RealGateInputs(now, limits, intent, wallet, source, buy));
            }
            return record(intent, wallet, verdict, now, started);
        } catch (RealGateUnavailable unavailable) {
            failedClosed(unavailable);
            log.warn(
                    "REAL barrier failed closed: dependency={}, transactionUsable={}, side={}, origin={}",
                    unavailable.dependency().label(),
                    unavailable.transactionUsable(),
                    intent.side(),
                    intent.origin());
            throw unavailable;
        }
    }

    /**
     * Counts the failure; a SQL failure already aborted the transaction in PostgreSQL, so it is also marked
     * rollback-only (review R-02). A lock timeout leaves it as it was: the caller decides.
     */
    private void failedClosed(RealGateUnavailable unavailable) {
        metrics.error(unavailable.dependency());
        if (!unavailable.transactionUsable()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private GateDecision record(
            OrderIntent intent, Optional<RealStateView> wallet, RealVerdict verdict, Instant now, long started) {
        UUID evaluationId = UUID.randomUUID();
        Optional<UUID> approvalId =
                verdict.approved() && intent.isBuy() ? Optional.of(UUID.randomUUID()) : Optional.empty();
        boolean inserted = store.insertEvaluation(
                new JdbcRealRiskStore.EvaluationRow(evaluationId, intent, verdict, approvalId, now));
        if (!inserted) {
            // A concurrent sell of the same decision recorded it first (U3-NFR4.2): its answer is the answer.
            return store.decisionOf(intent.tenantId(), intent.decisionId())
                    .orElseThrow(() -> new IllegalStateException("A conflicting REAL evaluation was not found"));
        }
        verdict.snapshotToRecord().ifPresent(store::insertSnapshot);
        if (approvalId.isPresent()) {
            store.insertReservation(new JdbcRealRiskStore.ReservationRow(
                    approvalId.get(),
                    intent.tenantId(),
                    intent.userId(),
                    wallet.orElseThrow().botWalletId(),
                    intent.botId(),
                    intent.decisionId(),
                    intent.mint(),
                    intent.inputLamports(),
                    now,
                    now.plus(limits.reservationTtl())));
        }
        if (!verdict.approved()) {
            audit.rejected(intent, evaluationId, verdict);
            log.info(
                    "REAL order refused: code={}, detail={}, side={}, origin={}, mint={}",
                    verdict.code(),
                    verdict.detail(),
                    intent.side(),
                    intent.origin(),
                    LogMasking.maskBase58(intent.mint()));
            if (verdict.pauseForDailyLoss()
                    && pauses.pauseUser(intent.tenantId(), intent.userId(), RealPauseReason.DAILY_LOSS)
                            == RealPauses.PauseOutcome.PAUSED) {
                metrics.dailyLossPause();
            }
        }
        metrics.evaluated(intent.side(), verdict, Duration.ofNanos(System.nanoTime() - started));
        return verdict.approved()
                ? new GateDecision.Approved(evaluationId, approvalId)
                : new GateDecision.Rejected(
                        evaluationId, verdict.code(), verdict.detail(), verdict.figures(), verdict.detailMint());
    }

    @Override
    public HandOverResult handOver(UUID tenantId, UUID approvalId) {
        Optional<JdbcRealRiskStore.Reservation> found = store.reservation(tenantId, approvalId);
        if (found.isEmpty()) {
            return HandOverResult.NOT_FOUND;
        }
        try {
            metrics.lockWaited(lock.acquire(found.get().userId()));
        } catch (RealGateUnavailable unavailable) {
            failedClosed(unavailable);
            throw unavailable;
        }
        JdbcRealRiskStore.Reservation reservation =
                store.reservation(tenantId, approvalId).orElseThrow();
        if (!reservation.open()) {
            return closed(reservation);
        }
        Instant now = clock.instant();
        if (!now.isBefore(reservation.expiresAt())) {
            if (store.closeReservation(approvalId, "EXPIRED", now)) {
                metrics.reservationsExpired(1);
            }
            return HandOverResult.RESERVATION_EXPIRED;
        }
        if (store.closeReservation(approvalId, "HANDED_OVER", now)) {
            return HandOverResult.HANDED_OVER;
        }
        return closed(store.reservation(tenantId, approvalId).orElseThrow());
    }

    @Override
    public <T> T withPersonLock(UUID tenantId, UUID userId, java.util.function.Supplier<T> action) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(userId, "userId");
        try {
            metrics.lockWaited(lock.acquire(userId));
        } catch (RealGateUnavailable unavailable) {
            failedClosed(unavailable);
            throw unavailable;
        }
        return action.get();
    }

    private static HandOverResult closed(JdbcRealRiskStore.Reservation reservation) {
        return "HANDED_OVER".equals(reservation.state())
                ? HandOverResult.ALREADY_HANDED_OVER
                : HandOverResult.RESERVATION_EXPIRED;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public BlockedToken blockToken(String chain, String mint, String reason, UUID adminId) {
        requireToken(chain, mint);
        if (reason == null || reason.isBlank() || reason.length() > BlockedToken.MAX_REASON) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The reason is required and has at most 500 characters");
        }
        if (adminId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The administrator is required");
        }
        BlockedToken token = new BlockedToken(chain, mint, reason.strip(), adminId, clock.instant());
        if (store.block(token)) {
            audit.blocked(token);
            return token;
        }
        return store.blocked(chain, mint)
                .orElseThrow(() -> new IllegalStateException("A blocked token disappeared during the block"));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public UnblockResult unblockToken(String chain, String mint, UUID adminId) {
        requireToken(chain, mint);
        if (adminId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The administrator is required");
        }
        Optional<BlockedToken> removed = store.unblock(chain, mint);
        removed.ifPresent(previous -> audit.unblocked(previous, adminId));
        return removed.isPresent() ? UnblockResult.UNBLOCKED : UnblockResult.NOT_BLOCKED;
    }

    private static void requireToken(String chain, String mint) {
        if (chain == null
                || chain.isBlank()
                || mint == null
                || !BASE58_ADDRESS.matcher(mint).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The token is a chain and a Base58 mint");
        }
    }
}
