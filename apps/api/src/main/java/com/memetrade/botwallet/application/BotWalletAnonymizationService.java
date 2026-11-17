package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.OpenExitsCheck;
import com.memetrade.botwallet.api.RealAnonymization;
import com.memetrade.botwallet.domain.AnonymizationStore;
import com.memetrade.botwallet.domain.AnonymizationStore.WalletToClose;
import com.memetrade.botwallet.domain.ReleaseStore;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.RealExecutionPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * M1 {@code closeForAnonymization} (261113-trading-real-bot U5, F9, BR6.1, BR6.2, U5-NFR1.3), in the transaction of the
 * anonymization, under the lock of the wallet:
 *
 * <ol>
 *   <li>261113-trading-real-bot U7 (BR6.9): refused with {@code OPEN_CONDITIONALS}, before anything else, while a
 *       conditional exit of the person is not final;
 *   <li>refused, with nothing written, while an order or a withdrawal is in progress ({@code ORDER_IN_PROGRESS}), while
 *       a token is held ({@code POSITION}) or while the ledger holds lamports ({@code BALANCE}): the person withdraws
 *       first, and the withdrawal is always available;
 *   <li>otherwise the wallet is CLOSED, the command DEACTIVATE (only the wallet id) is recorded for the TxSigner, the
 *       main wallet, the consent messages and the signed transactions of the withdrawals are cleared, the releases are
 *       revoked and the closing is audited (without any address);
 *   <li>LAST, after those events, the trail and the outbox lose the address of the bot wallet through the redaction
 *       function, so an event written in this very transaction is redacted too. The address itself leaves the wallet
 *       row when the TxSigner confirms the DEACTIVATE.
 * </ol>
 */
@Service
public class BotWalletAnonymizationService implements RealAnonymization {

    private static final Logger log = LoggerFactory.getLogger(BotWalletAnonymizationService.class);

    private final AnonymizationStore store;
    private final ReleaseStore releases;
    private final RealLedger ledger;
    private final RealExecutionPort execution;
    private final TxSignerCommandService commands;
    private final RealAudit audit;
    private final Clock clock;
    private final OpenExitsCheck openExits;

    public BotWalletAnonymizationService(
            AnonymizationStore store,
            ReleaseStore releases,
            RealLedger ledger,
            RealExecutionPort execution,
            TxSignerCommandService commands,
            RealAudit audit,
            Clock clock,
            OpenExitsCheck openExits) {
        this.openExits = openExits;
        this.store = store;
        this.releases = releases;
        this.ledger = ledger;
        this.execution = execution;
        this.commands = commands;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CloseOutcome closeForAnonymization(UUID tenantId, UUID userId) {
        Instant now = clock.instant();
        // 261113-trading-real-bot U7 (BR6.9, U7-NFR4.9): before any write, a conditional that is not final refuses it;
        // the anonymization never cancels a conditional by itself.
        if (openExits.hasOpenExits(tenantId, userId)) {
            log.info("REAL bot wallet not closed for the anonymization: reason=OPEN_CONDITIONALS");
            return new CloseOutcome.Refused("OPEN_CONDITIONALS");
        }
        Optional<WalletToClose> wallet = store.lockActiveWallet(tenantId, userId);
        if (wallet.isEmpty()) {
            store.clearPersonalData(tenantId, userId, now);
            releases.revokeOpen(userId, now, null);
            return new CloseOutcome.Closed(false);
        }
        UUID botWalletId = wallet.get().botWalletId();
        Optional<String> refusal = refusal(tenantId, userId, botWalletId);
        if (refusal.isPresent()) {
            log.info("REAL bot wallet not closed for the anonymization: reason={}", refusal.get());
            return new CloseOutcome.Refused(refusal.get());
        }
        store.closeWallet(botWalletId, now);
        UUID commandId = commands.deactivate(botWalletId);
        store.clearPersonalData(tenantId, userId, now);
        releases.revokeOpen(userId, now, null);
        audit.system(
                tenantId,
                "REAL_BOT_WALLET_CLOSED",
                "bot-wallet:" + botWalletId,
                Map.of("cause", "ANONYMIZATION", "commandId", commandId.toString()),
                null);
        int redacted = wallet.get().address() == null
                ? 0
                : store.redactAudit(wallet.get().address());
        log.info(
                "REAL bot wallet closed for the anonymization: botWalletId={}, auditRowsRedacted={}",
                botWalletId,
                redacted);
        return new CloseOutcome.Closed(true);
    }

    private Optional<String> refusal(UUID tenantId, UUID userId, UUID botWalletId) {
        RealBalance balance = ledger.balance(tenantId, botWalletId);
        boolean orderReserved = balance.openReservations().stream().anyMatch(ref -> ref.startsWith("order:"));
        if (orderReserved
                || store.withdrawalsInProgress(botWalletId) > 0
                || execution.ordersInTransit(tenantId, userId) > 0) {
            return Optional.of("ORDER_IN_PROGRESS");
        }
        if (!ledger.tokenBalances(tenantId, botWalletId).isEmpty()) {
            return Optional.of("POSITION");
        }
        if (balance.totalLamports() != 0) {
            return Optional.of("BALANCE");
        }
        return Optional.empty();
    }
}
