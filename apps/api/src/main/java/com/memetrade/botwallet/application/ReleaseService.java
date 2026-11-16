package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealAdministration.ReleaseView;
import com.memetrade.botwallet.api.RealAdministration.RevokeView;
import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.domain.RealRelease;
import com.memetrade.botwallet.domain.ReleaseFacts;
import com.memetrade.botwallet.domain.ReleaseRefusal;
import com.memetrade.botwallet.domain.ReleaseRules;
import com.memetrade.botwallet.domain.ReleaseStore;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.botwallet.infra.RealProperties;
import com.memetrade.identity.api.RealIdentity;
import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealFailureCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 261113-trading-real-bot U5 (F1, F2; BR1.2, BR1.3, BR1.6; U5-NFR4.1): the release and the revocation of a person by the
 * administrator, in the transaction of the caller. On the MAINNET the count of the guests and the insert run under one
 * advisory lock, so two requests at the same time never pass the limit of 20 (S27). The revocation cancels the unsent
 * orders through the M6 in the same transaction; the sent ones go on and the withdrawal never changes.
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final ReleaseStore store;
    private final RealIdentity identity;
    private final RealExecutionPort execution;
    private final RealAudit audit;
    private final RealProperties properties;
    private final Clock clock;

    public ReleaseService(
            ReleaseStore store,
            RealIdentity identity,
            RealExecutionPort execution,
            RealAudit audit,
            RealProperties properties,
            Clock clock) {
        this.store = store;
        this.identity = identity;
        this.execution = execution;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** BR1.2, BR1.3: registers the release on the network of the environment, in QUARANTINE for 24 hours. */
    @Transactional
    public ReleaseView release(UUID adminId, UUID userId, Network network) {
        Network environment = properties.network();
        if (network != environment) {
            throw RealAdminRefused.validation();
        }
        if (environment == Network.MAINNET) {
            store.lockMainnetReleases();
        }
        Optional<ReleaseStore.WalletOfPerson> wallet = store.activeWalletOf(userId);
        Optional<UUID> founder = properties.founder();
        ReleaseFacts facts = new ReleaseFacts(
                environment,
                wallet.isPresent(),
                wallet.isPresent() && identity.hasAcceptedRealTerms(wallet.get().tenantId(), userId),
                store.hasOpenRelease(userId, environment),
                founder.map(userId::equals).orElse(false),
                founder.map(f -> store.hasActiveRelease(f, Network.MAINNET)).orElse(false),
                environment == Network.MAINNET
                        ? store.openGuestReleases(Network.MAINNET, founder.orElse(new UUID(0, 0)))
                        : 0);
        Optional<ReleaseRefusal> refusal = ReleaseRules.refusal(facts);
        if (refusal.isPresent()) {
            throw new RealAdminRefused(refusal.get().name(), refusal.get().status());
        }
        RealRelease release = RealRelease.requested(userId, environment, adminId, clock.instant());
        if (!store.insert(release)) {
            throw new RealAdminRefused(ReleaseRefusal.RELEASE_EXISTS.name(), ReleaseRefusal.RELEASE_EXISTS.status());
        }
        audit.admin(
                adminId,
                wallet.map(ReleaseStore.WalletOfPerson::tenantId),
                "REAL_RELEASE_REQUESTED",
                "user:" + userId,
                Map.of(
                        "releaseId", release.releaseId().toString(),
                        "network", environment.name(),
                        "effectiveAt", release.effectiveAt().toString()));
        log.info(
                "REAL release requested: releaseId={}, network={}, effectiveAt={}",
                release.releaseId(),
                environment,
                release.effectiveAt());
        return new ReleaseView(
                release.releaseId(), userId, environment.name(), release.state().name(), release.effectiveAt());
    }

    /**
     * BR1.6: revokes at once and cancels the unsent orders of the person (REAL_DISABLED), in the transaction of the
     * caller, which already holds the lock of the person. Revoking again changes nothing.
     */
    @Transactional
    public RevokeView revoke(UUID adminId, UUID userId, String reason) {
        Instant now = clock.instant();
        List<UUID> revoked = store.revokeOpen(userId, now, adminId);
        if (revoked.isEmpty()) {
            return new RevokeView(userId, false, 0);
        }
        Optional<UUID> tenant = store.tenantOf(userId);
        int cancelled = tenant.map(
                        t -> execution.cancelUnsent(CancelScope.user(t, userId), RealFailureCode.REAL_DISABLED))
                .orElse(0);
        audit.admin(
                adminId,
                tenant,
                "REAL_RELEASE_REVOKED",
                "user:" + userId,
                Map.of(
                        "releases", Integer.toString(revoked.size()),
                        "cancelledOrders", Integer.toString(cancelled),
                        "reason", reason == null ? "" : reason));
        log.info("REAL release revoked: releases={}, cancelledOrders={}", revoked.size(), cancelled);
        return new RevokeView(userId, true, cancelled);
    }

    /** The tenant of the person, from its bot wallet. */
    public Optional<UUID> tenantOf(UUID userId) {
        return store.tenantOf(userId);
    }
}
