package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainPort.SignatureInfo;
import com.memetrade.botwallet.domain.RestoreCheck;
import com.memetrade.botwallet.domain.RestoreStore;
import com.memetrade.botwallet.domain.RestoreStore.Marker;
import com.memetrade.botwallet.domain.RestoreStore.WalletToCheck;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.realexecution.api.RealExecutionPort;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261113-trading-real-bot U5 (F10, BR2.5, U5-NFR4.4, reliability-design.md "Restauração"): the restore check of the
 * worker.
 *
 * <ol>
 *   <li>At the start of the worker the marker RESTORE_CHECK_PENDING opens BEFORE any read; while it is open new REAL
 *       orders are refused (a global pause) and the deposit scan waits, so its cursor never passes a signature that was
 *       not checked. The withdrawal goes on (BR2.4).
 *   <li>For every bot wallet, the history newer than its cursor is read page by page (1.000) until it ends; without a
 *       cursor, from the creation of the wallet. Each signature the database does not know (the orders through the M6,
 *       the withdrawals and the deposits) is looked up: signed by the bot wallet, it is unknown.
 *   <li>Something unknown: the marker becomes the global pause RESTORE, which leaves only when every wallet reconciled
 *       CLEAN after it (AFTER_RESTORE) and the administrator confirmed. Nothing: the marker is cleared.
 *   <li>The RPC down: the marker stays pending and the job tries again every minute.
 * </ol>
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);
    private static final int PAGE = 1_000;

    private final RestoreStore store;
    private final ChainPort chain;
    private final RealExecutionPort execution;
    private final RealAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RestoreService(
            RestoreStore store,
            ChainPort chain,
            RealExecutionPort execution,
            RealAudit audit,
            TransactionTemplate transactions,
            Clock clock) {
        this.store = store;
        this.chain = chain;
        this.execution = execution;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** What one check found. */
    public enum Outcome {
        NOTHING,
        PENDING,
        CLEAN,
        RESTORE
    }

    /** The start of the worker: the marker opens before anything is read (idempotent across instances). */
    public void openCheck() {
        if (store.openCheckPending(clock.instant())) {
            log.info("REAL restore check opened at the start of the worker");
        }
    }

    /** One check while the marker is pending; returns what it found. */
    public Outcome check() {
        Optional<Marker> marker = store.openMarker();
        if (marker.isEmpty() || !marker.get().pending()) {
            return Outcome.NOTHING;
        }
        int unknown = 0;
        try {
            for (WalletToCheck wallet : store.walletsToCheck()) {
                unknown += unknownOf(wallet);
            }
        } catch (RuntimeException unavailable) {
            log.warn(
                    "REAL restore check pending, the network did not answer: {}",
                    unavailable.getClass().getSimpleName());
            return Outcome.PENDING;
        }
        Instant now = clock.instant();
        UUID markerId = marker.get().markerId();
        if (unknown == 0) {
            store.clear(markerId, null, now);
            log.info("REAL restore check clean: nothing unknown");
            return Outcome.CLEAN;
        }
        int found = unknown;
        transactions.executeWithoutResult(status -> {
            store.toRestore(markerId, found, now);
            audit.global(
                    "REAL_RESTORE_DETECTED",
                    "restore:" + markerId,
                    Map.of("unknownSignatures", Integer.toString(found)));
        });
        log.warn("REAL restore detected: {} signatures of bot wallets unknown to the database", found);
        return Outcome.RESTORE;
    }

    private int unknownOf(WalletToCheck wallet) {
        List<SignatureInfo> history = new ArrayList<>();
        String before = null;
        while (true) {
            List<SignatureInfo> page = chain.signaturesSince(
                            ChainPort.Purpose.RESTORE_CHECK, wallet.address(), wallet.depositCursor(), before, PAGE)
                    .signatures();
            history.addAll(page);
            if (page.size() < PAGE) {
                break;
            }
            before = page.getLast().signature();
        }
        if (history.isEmpty()) {
            return 0;
        }
        List<String> signatures = history.stream().map(SignatureInfo::signature).toList();
        Set<String> known = new HashSet<>(store.knownSignatures(wallet.botWalletId(), signatures));
        known.addAll(execution.knownTransactions(signatures));
        return RestoreCheck.unknownSignedBy(wallet.address(), history, known, chain::transactionSigners);
    }

    /**
     * BR2.5: the administrator confirms the restore after every wallet reconciled CLEAN after it; the global pause
     * RESTORE leaves.
     *
     * @throws RealAdminRefused {@code NOT_FOUND} without a restore, {@code RESTORE_NOT_CLEAN} before every wallet is
     *     clean
     */
    @Transactional
    public Marker confirm(UUID adminId) {
        Marker marker = store.openMarker().filter(open -> !open.pending()).orElseThrow(RealAdminRefused::notFound);
        if (!store.everyWalletCleanAfter(marker.detectedAt())) {
            throw new RealAdminRefused("RESTORE_NOT_CLEAN", 409);
        }
        store.clear(marker.markerId(), adminId, clock.instant());
        audit.admin(adminId, Optional.empty(), "REAL_RESTORE_CONFIRMED", "restore:" + marker.markerId(), Map.of());
        log.info("REAL restore confirmed by the administrator; the RESTORE pause leaves");
        return marker;
    }
}
