package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.api.RealWalletRef;
import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.RealReleases;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.botwallet.infra.RealProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M1 for the other modules (261113-trading-real-bot U3, BR7.3): the REAL state of a bot wallet and the pause and resume
 * of one reason. Tenant and person always filter the wallet (BR6.3). A pause or a resume joins the transaction of the
 * caller (the cycle of the bot, the job of the barrier), is one atomic statement on {@code real_preferences}, and is
 * audited ({@code REAL_PAUSED}, {@code REAL_RESUMED}, actor {@code system}) only when it changed something.
 */
@Service
public class RealStateService implements RealStateQuery, RealPauses {

    private final BotWalletRepository repository;
    private final RealAudit audit;
    private final RealProperties properties;
    private final Clock clock;

    public RealStateService(BotWalletRepository repository, RealAudit audit, RealProperties properties, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<RealStateView> readRealState(UUID tenantId, UUID userId) {
        Optional<BotWalletAccount> wallet = repository.activeWallet(tenantId, userId);
        if (wallet.isEmpty()) {
            return Optional.empty();
        }
        UUID botWalletId = wallet.get().botWalletId();
        Preferences preferences = repository.preferences(botWalletId);
        Map<PauseReason, Instant> recorded = repository.pausedSince(botWalletId);
        Set<RealPauseReason> reasons = EnumSet.noneOf(RealPauseReason.class);
        Map<RealPauseReason, Instant> since = new EnumMap<>(RealPauseReason.class);
        for (PauseReason reason : preferences.pausedReasons()) {
            RealPauseReason exposed = RealPauseReason.valueOf(reason.name());
            reasons.add(exposed);
            since.put(exposed, recorded.getOrDefault(reason, preferences.updatedAt()));
        }
        Instant now = clock.instant();
        return Optional.of(new RealStateView(
                botWalletId,
                properties.enabled(),
                RealReleases.isEffective(repository.releases(userId), properties.network(), now),
                RealOperationMode.valueOf(preferences.operationMode().name()),
                preferences.capLamports(),
                preferences.feeReserveLamports(),
                reasons,
                since));
    }

    @Override
    public List<RealWalletRef> walletsInForce() {
        if (!properties.enabled()) {
            return List.of();
        }
        return repository.walletsWithReleaseInForce(properties.network(), clock.instant()).stream()
                .map(wallet -> new RealWalletRef(wallet.tenantId(), wallet.userId(), wallet.botWalletId()))
                .toList();
    }

    @Override
    public List<PausedWallet> pausedWallets(RealPauseReason reason) {
        return repository.walletsPausedFor(PauseReason.valueOf(reason.name())).stream()
                .map(row -> new PausedWallet(
                        new RealWalletRef(row.tenantId(), row.userId(), row.botWalletId()), row.since()))
                .toList();
    }

    @Override
    @Transactional
    public PauseOutcome pauseUser(UUID tenantId, UUID userId, RealPauseReason reason) {
        Optional<BotWalletAccount> wallet = repository.activeWallet(tenantId, userId);
        if (wallet.isEmpty()) {
            return PauseOutcome.NO_WALLET;
        }
        UUID botWalletId = wallet.get().botWalletId();
        if (!repository.addPause(botWalletId, PauseReason.valueOf(reason.name()), clock.instant())) {
            return PauseOutcome.ALREADY_PAUSED;
        }
        audit.system(tenantId, "REAL_PAUSED", "bot-wallet:" + botWalletId, Map.of("reason", reason.name()), null);
        return PauseOutcome.PAUSED;
    }

    @Override
    @Transactional
    public PauseOutcome resumeUser(UUID tenantId, UUID userId, RealPauseReason reason) {
        Optional<BotWalletAccount> wallet = repository.activeWallet(tenantId, userId);
        if (wallet.isEmpty()) {
            return PauseOutcome.NO_WALLET;
        }
        UUID botWalletId = wallet.get().botWalletId();
        if (!repository.removePause(botWalletId, PauseReason.valueOf(reason.name()))) {
            return PauseOutcome.NOT_PAUSED;
        }
        audit.system(tenantId, "REAL_RESUMED", "bot-wallet:" + botWalletId, Map.of("reason", reason.name()), null);
        return PauseOutcome.RESUMED;
    }
}
