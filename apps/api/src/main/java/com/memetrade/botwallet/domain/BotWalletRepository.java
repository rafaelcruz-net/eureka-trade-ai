package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the bot wallet for the API (BR6.4): every read takes the tenant and the person from the
 * authentication; a resource of another account is simply not found.
 */
public interface BotWalletRepository {

    Optional<BotWalletAccount> activeWallet(UUID tenantId, UUID userId);

    /** The active wallet locked FOR UPDATE for the rest of the transaction (BR4.9); waits at most 2 s. */
    Optional<BotWalletAccount> lockActiveWallet(UUID tenantId, UUID userId);

    Preferences preferences(UUID botWalletId);

    /**
     * Saves mode, cap, slippage and the USER pause of the person. Since the U3 (review R-06) the other pause reasons
     * are NOT taken from {@code preferences}: they stay as they are in the row and change only through
     * {@link #addPause} and {@link #removePause}, so a concurrent pause of the barrier is never overwritten.
     */
    void savePreferences(UUID botWalletId, Preferences preferences);

    /** Inserts the wallet, its preferences and the accepted consent. */
    void insertWallet(BotWalletAccount wallet, Preferences preferences, AcceptedConsent consent);

    void insertChallenge(ConsentChallenge challenge);

    Optional<ConsentChallenge> challenge(UUID tenantId, UUID userId, UUID challengeId);

    void updateChallenge(ConsentChallenge challenge);

    /** BR1.8: records a used nonce; {@code false} when it was already used. */
    boolean useNonce(String nonce, Instant purgeAfter);

    List<RealRelease> releases(UUID userId);

    void insertRelease(RealRelease release);

    void insertWithdrawal(Withdrawal withdrawal);

    Optional<Withdrawal> withdrawal(UUID tenantId, UUID botWalletId, UUID withdrawalId);

    List<Withdrawal> withdrawals(UUID tenantId, UUID botWalletId, int limit);

    boolean hasAmbiguousWithdrawal(UUID botWalletId);

    /** 261113-trading-real-bot U4 (BR8.2): the withdrawal recorded with this idempotency key in the tenant. */
    Optional<UUID> withdrawalIdByKey(UUID tenantId, UUID idempotencyKey);

    List<DepositView> deposits(UUID tenantId, UUID botWalletId, int limit);

    /** NFR4.7: IN_USE challenges older than the instant go back to PENDING; returns how many. */
    int releaseStuckChallenges(Instant inUseBefore);

    /**
     * 261113-trading-real-bot U3 (M1, BR4.5): the start of each active pause of the wallet. A reason recorded before
     * the U3, without a start, is absent from the map.
     */
    Map<PauseReason, Instant> pausedSince(UUID botWalletId);

    /** M1 {@code pauseUser}: adds the reason with its start in one statement; {@code false} when it was already there. */
    boolean addPause(UUID botWalletId, PauseReason reason, Instant at);

    /** M1 {@code resumeUser}: removes the reason and its start; {@code false} when it was not there. */
    boolean removePause(UUID botWalletId, PauseReason reason);

    /** BR4.4: every ACTIVE wallet whose person has a release in force on {@code network} at {@code now}. */
    List<BotWalletAccount> walletsWithReleaseInForce(Network network, Instant now);

    /** BR4.5: every ACTIVE wallet paused for {@code reason}, with the start of that pause. */
    List<PausedWalletRow> walletsPausedFor(PauseReason reason);

    /** One wallet paused for a reason; {@code since} falls back to the last change of the preferences. */
    record PausedWalletRow(UUID tenantId, UUID userId, UUID botWalletId, Instant since) {}

    /** A deposit as shown to its owner. */
    record DepositView(String txSignature, long lamports, Instant finalizedAt, boolean knownSource) {}

    /** The consent accepted at activation or at a raise (entities.md WalletConsent). */
    record AcceptedConsent(
            UUID consentId,
            ConsentKind kind,
            String nonce,
            Instant expiresAt,
            OperationMode mode,
            long capLamports,
            String digest,
            Instant signedAt) {}
}
