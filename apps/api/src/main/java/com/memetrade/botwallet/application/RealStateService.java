package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealReleaseInfo;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.api.RealWalletRef;
import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.RealReleases;
import com.memetrade.botwallet.infra.GlobalPauses;
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
 *
 * <p>261113-trading-real-bot U5 (logical-components.md "Mudanças em outras Units", BR2.5, BR3.1, U5-NFR3.5): the state
 * also carries the GLOBAL pauses read live ({@code KILL_SWITCH}, {@code RESTORE}, {@code RESTORE_CHECK_PENDING}) with
 * their start, so the barrier of the U3 and the recheck of the U4 refuse a new order the moment one commits, for every
 * wallet, even one created afterwards.
 */
@Service
public class RealStateService implements RealStateQuery, RealPauses {

    private final BotWalletRepository repository;
    private final RealAudit audit;
    private final RealProperties properties;
    private final GlobalPauses global;
    private final Clock clock;

    public RealStateService(
            BotWalletRepository repository,
            RealAudit audit,
            RealProperties properties,
            GlobalPauses global,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
        this.global = global;
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
        global.active().forEach((reason, start) -> {
            RealPauseReason exposed = RealPauseReason.valueOf(reason.name());
            reasons.add(exposed);
            since.put(exposed, start);
        });
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

    /**
     * 261113-trading-real-bot U6: the person comes from the authentication of the caller (tenant and person of the
     * token); the releases are read by that person only, so another account is never seen.
     */
    @Override
    public RealReleaseInfo readRelease(UUID tenantId, UUID userId) {
        RealReleases.Current current = RealReleases.current(repository.releases(userId), properties.network());
        return new RealReleaseInfo(
                properties.enabled(),
                properties.network().name(),
                current.state(),
                current.effectiveAt(),
                current.blockedReason());
    }

    @Override
    public List<UUID> botWalletIds(UUID tenantId, UUID userId) {
        return repository.walletIdsOf(tenantId, userId);
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
