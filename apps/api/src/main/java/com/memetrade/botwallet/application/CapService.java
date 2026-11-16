package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealAdministration.CapView;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.CapRules;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.ReleaseStore;
import com.memetrade.botwallet.infra.RealAudit;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 261113-trading-real-bot U5 (F7, BR4.1, S18, S19): the administrator lowers the cap of a person. The new cap is saved in
 * the preferences of the ACTIVE wallet (the other pauses stay) and holds at once for the barrier (it reads the M1); the
 * command LOWER_CAP is recorded in the same transaction and sent to the TxSigner by the job until it confirms.
 */
@Service
public class CapService {

    private static final Logger log = LoggerFactory.getLogger(CapService.class);

    private final ReleaseStore wallets;
    private final BotWalletRepository repository;
    private final TxSignerCommandService commands;
    private final RealAudit audit;
    private final Clock clock;

    public CapService(
            ReleaseStore wallets,
            BotWalletRepository repository,
            TxSignerCommandService commands,
            RealAudit audit,
            Clock clock) {
        this.wallets = wallets;
        this.repository = repository;
        this.commands = commands;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @throws RealAdminRefused {@code NOT_FOUND} without an ACTIVE wallet, {@code CAP_ONLY_LOWERED} for an equal or
     *     higher cap, {@code VALIDATION_FAILED} for a negative one
     */
    @Transactional
    public CapView lower(UUID adminId, UUID userId, long capLamports) {
        ReleaseStore.WalletOfPerson wallet = wallets.activeWalletOf(userId).orElseThrow(RealAdminRefused::notFound);
        Preferences current = repository.preferences(wallet.botWalletId());
        Optional<String> refusal = CapRules.refusal(current.capLamports(), capLamports);
        if (refusal.isPresent()) {
            throw new RealAdminRefused(refusal.get(), 422);
        }
        Instant now = clock.instant();
        repository.savePreferences(wallet.botWalletId(), current.lowerCap(capLamports, now));
        UUID commandId = commands.lowerCap(wallet.botWalletId(), capLamports);
        audit.admin(
                adminId,
                Optional.of(wallet.tenantId()),
                "REAL_CAP_LOWERED",
                "user:" + userId,
                Map.of(
                        "fromLamports", Long.toString(current.capLamports()),
                        "toLamports", Long.toString(capLamports),
                        "commandId", commandId.toString()));
        log.info("REAL cap lowered by the administrator: commandId={}", commandId);
        return new CapView(userId, capLamports, commandId);
    }
}
