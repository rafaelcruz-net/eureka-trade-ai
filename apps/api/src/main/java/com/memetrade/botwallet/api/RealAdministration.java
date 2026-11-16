package com.memetrade.botwallet.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M1 for the administration of the REAL (261113-trading-real-bot U5, contract R4 and M1 {@code adminRelease},
 * {@code adminRevoke}, {@code adminLowerCap}, {@code adminUserView}, {@code listDivergences}, {@code compensate}): the
 * only interface the {@code RealAdminController} of {@code administration.web} uses for the bot wallets. Every change
 * joins the transaction of the caller (the controller opens it with the common idempotency record); the revocation runs
 * inside the lock of the person of the barrier (M5 {@code withPersonLock}), which the controller takes, so
 * {@code botwallet} never depends on {@code riskcontrol}. Refusals are {@link RealAdminRefused}. No view carries a key
 * nor the full main wallet.
 */
public interface RealAdministration {

    /**
     * BR1.2, BR1.3: registers the release of a person on the network of the environment; it starts in QUARANTINE and
     * takes effect after 24 hours and green gates.
     *
     * @param network must be the network of the environment ({@code VALIDATION_FAILED} otherwise)
     * @throws RealAdminRefused {@code NO_BOT_WALLET}, {@code TERMS_V3_MISSING}, {@code RELEASE_EXISTS},
     *     {@code FOUNDER_FIRST}, {@code PILOT_FULL} or {@code VALIDATION_FAILED}
     */
    ReleaseView adminRelease(UUID adminId, UUID userId, String network);

    /**
     * BR1.6: revokes the releases of the person at once and cancels its unsent orders (M6 {@code cancelUnsent}); the
     * sent ones go on and the withdrawal never changes. Revoking a person already revoked changes nothing.
     */
    RevokeView adminRevoke(UUID adminId, UUID userId, String reason);

    /** The tenant of the person, from its bot wallet, for the lock of the person (empty without any bot wallet). */
    Optional<UUID> tenantOf(UUID userId);

    /** BR4.1: lowers the cap at once and sends LOWER_CAP to the TxSigner until it confirms. */
    CapView adminLowerCap(UUID adminId, UUID userId, long capLamports);

    /** BR5.1: the view of one person with a bot wallet (the 20 newest orders included). */
    Optional<RealUserView> adminUserView(UUID userId);

    /** BR5.1: the people with a bot wallet, oldest wallet first, by cursor (the recent orders only in the detail). */
    Page<RealUserView> adminUsers(Optional<String> cursor, int limit);

    /** R4: the divergences OPEN or COMPENSATED, newest first, by cursor. */
    Page<DivergenceView> listDivergences(Optional<String> cursor, int limit);

    /** BR2.3: the compensation of a divergence; each item must be the measure of its asset. */
    DivergenceView compensate(UUID adminId, UUID divergenceId, List<CompensationItem> items, String reason);

    /** BR2.5: confirms the restore once every wallet reconciled CLEAN after it; the global pause RESTORE leaves. */
    RestoreView confirmRestore(UUID adminId);

    /** A release as the administrator sees it. */
    record ReleaseView(UUID releaseId, UUID userId, String network, String state, Instant effectiveAt) {}

    /** The result of a revocation: whether something was revoked and how many orders were cancelled. */
    record RevokeView(UUID userId, boolean revoked, int cancelledOrders) {}

    /**
     * A divergence as the administrator sees it (R4 {@code Divergence}): the differences measured by its last
     * reconciliation, network minus ledger, in lamports and in base units of each mint. No address.
     */
    record DivergenceView(
            UUID divergenceId,
            UUID botWalletId,
            String state,
            long lamportsDiff,
            List<TokenDiffView> tokenDiffs,
            UUID lastRunId,
            Instant openedAt) {

        public DivergenceView {
            tokenDiffs = List.copyOf(tokenDiffs);
        }
    }

    /** One mint of a divergence: on the network, in the ledger, and the difference with its sign. */
    record TokenDiffView(String mint, long chain, long ledger, long diff) {}

    /** The cap after the administrator lowered it, and the command sent to the TxSigner. */
    record CapView(UUID userId, long capLamports, UUID commandId) {}

    /** One item of a compensation: {@code SOL} or a mint, and the signed amount (lamports or base units). */
    record CompensationItem(String asset, long amount) {}

    /** The restore confirmed. */
    record RestoreView(UUID markerId, Instant detectedAt) {}

    /** A page of a list by cursor. */
    record Page<T>(List<T> items, String nextCursor) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * BR5.1 (Q9, entities.md RealUserView): the REAL of one person. The main wallet only abbreviated (4 first … 4 last);
     * the bot wallet in full (it is public and the administrator needs it to look at the network); never a key nor a
     * reference of a key.
     */
    record RealUserView(
            UUID userId,
            UUID botWalletId,
            String walletStatus,
            String mainWalletShort,
            String botWalletAddress,
            String mode,
            long capLamports,
            BalanceView balance,
            ReleaseSummary release,
            Set<String> pausedReasons,
            List<OrderSummary> recentOrders,
            List<DivergenceView> openDivergences) {

        public RealUserView {
            pausedReasons = Set.copyOf(pausedReasons);
            recentOrders = List.copyOf(recentOrders);
            openDivergences = List.copyOf(openDivergences);
        }
    }

    /** The REAL balance: total, free, reserved and what is above the cap (only for withdrawal). */
    record BalanceView(long totalLamports, long freeLamports, long reservedLamports, long aboveCapLamports) {}

    /** The newest release of the person on the network of the environment ({@code NONE} when there is none). */
    record ReleaseSummary(String state, String network, Instant effectiveAt, String blockedReason) {}

    /** One recent REAL order. */
    record OrderSummary(UUID realOrderId, String side, String mint, String state, Instant createdAt) {}
}
