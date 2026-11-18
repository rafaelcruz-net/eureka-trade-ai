package com.memetrade.botwallet.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.botwallet.application.BotWalletViews.BotWalletView;
import com.memetrade.botwallet.application.BotWalletViews.ChallengeView;
import com.memetrade.botwallet.domain.ActivationGate;
import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletError;
import com.memetrade.botwallet.domain.BotWalletException;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.BotWalletRepository.AcceptedConsent;
import com.memetrade.botwallet.domain.ChallengeRules;
import com.memetrade.botwallet.domain.ChallengeValues;
import com.memetrade.botwallet.domain.ConsentChallenge;
import com.memetrade.botwallet.domain.ConsentKind;
import com.memetrade.botwallet.domain.ConsentMessages;
import com.memetrade.botwallet.domain.OperationMode;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.RealReleases;
import com.memetrade.botwallet.domain.ReconciliationStore;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.botwallet.domain.Withdrawal;
import com.memetrade.botwallet.domain.WithdrawalAmount;
import com.memetrade.botwallet.domain.WithdrawalFees;
import com.memetrade.botwallet.domain.WithdrawalLimit;
import com.memetrade.botwallet.domain.WithdrawalLimits;
import com.memetrade.botwallet.domain.WithdrawalTransition;
import com.memetrade.botwallet.infra.GlobalPauses;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.botwallet.infra.RealProperties;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.RealIdentity;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.shared.solana.Base58Codec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service of the routes R2 (functional-spec.md F1, F2, F4, F5). Tenant and person come only from the
 * authentication (BR6.4). Only the routes that open risk check the REAL flag and the release; reading, lowering,
 * pausing and withdrawing never do (FR3.3, BR4.2). The TxSigner is called OUTSIDE a database transaction; the
 * challenge stays IN_USE meanwhile and goes back to PENDING when the TxSigner does not answer (BR1.10).
 */
@Service
public class BotWalletService {

    private static final Duration NONCE_RETENTION = Duration.ofDays(1);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PAGE = 100;

    private final BotWalletRepository repository;
    private final RealIdentity identity;
    private final RealLedger ledger;
    private final TxSignerPort signer;
    private final RealAudit audit;
    private final IdempotencyGuard idempotency;
    private final RealProperties properties;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ReconciliationStore reconciliations;
    private final GlobalPauses globalPauses;

    public BotWalletService(
            BotWalletRepository repository,
            RealIdentity identity,
            RealLedger ledger,
            TxSignerPort signer,
            RealAudit audit,
            IdempotencyGuard idempotency,
            RealProperties properties,
            Clock clock,
            ObjectMapper json,
            PlatformTransactionManager transactionManager,
            ReconciliationStore reconciliations,
            GlobalPauses globalPauses) {
        this.repository = repository;
        this.identity = identity;
        this.ledger = ledger;
        this.signer = signer;
        this.audit = audit;
        this.idempotency = idempotency;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.reconciliations = reconciliations;
        this.globalPauses = globalPauses;
    }

    // ----- F1: challenge and activation -----

    public ChallengeView issueChallenge(Principal person, ConsentKind kind, OperationMode mode, Long capLamports) {
        Instant now = clock.instant();
        Optional<BotWalletAccount> wallet = repository.activeWallet(person.tenantId(), person.userId());
        if (kind == ConsentKind.ACTIVATE) {
            requireActivationGate(person, wallet.isPresent(), now);
        } else {
            requireRiskOpening(person, now);
            if (wallet.isEmpty()) {
                throw new BotWalletException(BotWalletError.BOT_WALLET_NOT_ACTIVE);
            }
        }
        Preferences current =
                wallet.map(w -> repository.preferences(w.botWalletId())).orElse(null);
        ChallengeValues values = ChallengeRules.values(kind, mode, capLamports, current, properties.maxCapLamports());
        String main = mainWallet(person);
        String nonce = newNonce();
        String message = ConsentMessages.render(new ConsentMessages.Fields(
                kind,
                properties.network(),
                main,
                wallet.map(BotWalletAccount::publicAddress).orElse(null),
                values.mode(),
                values.capLamports(),
                nonce,
                now));
        ConsentChallenge challenge = ConsentChallenge.issue(
                person.tenantId(), person.userId(), kind, message, nonce, values.mode(), values.capLamports(), now);
        transactions.executeWithoutResult(status -> repository.insertChallenge(challenge));
        return new ChallengeView(challenge.challengeId(), message, nonce, challenge.expiresAt());
    }

    public BotWalletView activate(Principal person, UUID challengeId, String signature) {
        Instant now = clock.instant();
        ConsentChallenge locked = transactions.execute(status -> {
            boolean hasActive =
                    repository.activeWallet(person.tenantId(), person.userId()).isPresent();
            requireActivationGate(person, hasActive, now);
            ConsentChallenge challenge = challenge(person, challengeId, ConsentKind.ACTIVATE);
            ConsentChallenge inUse = challenge.lock(now);
            repository.updateChallenge(inUse);
            return inUse;
        });
        String main = mainWallet(person);
        if (!identity.verifyConsentSignature(main, locked.message(), signature)) {
            throw refuse(person, locked, BotWalletError.CONSENT_INVALID, "BAD_SIGNATURE");
        }
        String address;
        try {
            address = signer.createWallet(challengeId, main, locked.message(), signature);
        } catch (TxSignerPort.SignerUnavailableException down) {
            transactions.executeWithoutResult(status -> repository.updateChallenge(locked.release()));
            throw new BotWalletException(BotWalletError.UPSTREAM_UNAVAILABLE);
        } catch (TxSignerPort.SignerRefusedException refused) {
            throw refuse(person, locked, errorOfSigner(refused.code()), refused.code());
        }
        return transactions.execute(status -> createWallet(person, locked, address, main, now));
    }

    private BotWalletView createWallet(
            Principal person, ConsentChallenge challenge, String address, String main, Instant now) {
        repository.updateChallenge(challenge.consume(now));
        if (!repository.useNonce(challenge.nonce(), challenge.expiresAt().plus(NONCE_RETENTION))) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        BotWalletAccount wallet =
                BotWalletAccount.activate(person.tenantId(), person.userId(), address, main, properties.network(), now);
        Preferences preferences = Preferences.initial(
                challenge.capLamports(),
                properties.feeReserveLamports(),
                properties.maxWithdrawalFeeLamports(),
                properties.maxCapLamports(),
                now);
        try {
            repository.insertWallet(wallet, preferences, accepted(challenge, now));
        } catch (DuplicateKeyException race) {
            throw new BotWalletException(BotWalletError.BOT_WALLET_ALREADY_ACTIVE);
        }
        audit.person(
                person.tenantId(),
                person.userId(),
                "REAL_BOT_WALLET_CREATED",
                "bot-wallet:" + wallet.botWalletId(),
                Map.of("capLamports", Long.toString(preferences.capLamports())),
                RealReferences.botWallet(address),
                main);
        audit.person(
                person.tenantId(),
                person.userId(),
                "REAL_CONSENT_ACCEPTED",
                "bot-wallet:" + wallet.botWalletId(),
                Map.of("kind", challenge.kind().name()),
                RealReferences.botWallet(address),
                null);
        return BotWalletViews.of(
                wallet, preferences, RealBalances.EMPTY, now, java.util.Set.of(), extras(person, wallet, preferences));
    }

    // ----- F2: preferences, pause and resume -----

    public BotWalletView changePreferences(Principal person, PreferencesChange change) {
        Instant now = clock.instant();
        BotWalletAccount wallet = activeWallet(person);
        Preferences current = repository.preferences(wallet.botWalletId());
        if (Boolean.FALSE.equals(change.paused())) {
            throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
        }
        Preferences next = current;
        if (change.capLamports() != null && change.capLamports() > current.capLamports()) {
            next = raiseCap(person, wallet, current, change, now);
        } else if (change.capLamports() != null) {
            next = next.lowerCap(change.capLamports(), now);
            lowerAtSigner(wallet, change.capLamports());
        }
        if (change.maxSlippageBps() != null) {
            next = next.withSlippage(change.maxSlippageBps(), now);
        }
        if (Boolean.TRUE.equals(change.paused())) {
            next = next.pause(PauseReason.USER, now);
        }
        Preferences saved = next;
        transactions.executeWithoutResult(status -> {
            repository.savePreferences(wallet.botWalletId(), saved);
            audit.person(
                    person.tenantId(),
                    person.userId(),
                    "REAL_PREFERENCES_CHANGED",
                    "bot-wallet:" + wallet.botWalletId(),
                    Map.of(
                            "capLamports",
                            Long.toString(saved.capLamports()),
                            "paused",
                            Boolean.toString(saved.isPaused())),
                    null,
                    null);
        });
        return view(person);
    }

    private Preferences raiseCap(
            Principal person, BotWalletAccount wallet, Preferences current, PreferencesChange change, Instant now) {
        requireRiskOpening(person, now);
        if (change.challengeId() == null || change.signature() == null) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        ConsentChallenge locked = transactions.execute(status -> {
            ConsentChallenge challenge = challenge(person, change.challengeId(), ConsentKind.RAISE_CAP);
            if (challenge.capLamports() != change.capLamports()) {
                throw new BotWalletException(BotWalletError.CONSENT_INVALID);
            }
            ConsentChallenge inUse = challenge.lock(now);
            repository.updateChallenge(inUse);
            return inUse;
        });
        if (!identity.verifyConsentSignature(wallet.mainWalletAddress(), locked.message(), change.signature())) {
            throw refuse(person, locked, BotWalletError.CONSENT_INVALID, "BAD_SIGNATURE");
        }
        try {
            signer.raise(
                    locked.challengeId(),
                    wallet.publicAddress(),
                    ConsentKind.RAISE_CAP,
                    locked.message(),
                    change.signature());
        } catch (TxSignerPort.SignerUnavailableException down) {
            transactions.executeWithoutResult(status -> repository.updateChallenge(locked.release()));
            throw new BotWalletException(BotWalletError.UPSTREAM_UNAVAILABLE);
        } catch (TxSignerPort.SignerRefusedException refused) {
            throw refuse(person, locked, errorOfSigner(refused.code()), refused.code());
        }
        transactions.executeWithoutResult(status -> {
            repository.updateChallenge(locked.consume(now));
            repository.useNonce(locked.nonce(), locked.expiresAt().plus(NONCE_RETENTION));
        });
        return current.raiseCap(change.capLamports(), now);
    }

    /** BR2.2: the TxSigner only restricts; when it is down the api keeps the more restrictive value anyway. */
    private void lowerAtSigner(BotWalletAccount wallet, long capLamports) {
        try {
            signer.lowerCap(UUID.randomUUID(), wallet.publicAddress(), capLamports);
        } catch (TxSignerPort.SignerUnavailableException | TxSignerPort.SignerRefusedException later) {
            org.slf4j.LoggerFactory.getLogger(BotWalletService.class)
                    .warn("LOWER_CAP not delivered to the TxSigner; the api applies the lower cap");
        }
    }

    public BotWalletView resume(Principal person) {
        Instant now = clock.instant();
        BotWalletAccount wallet = activeWallet(person);
        Preferences resumed = repository.preferences(wallet.botWalletId()).resumeByUser(now);
        transactions.executeWithoutResult(status -> {
            repository.savePreferences(wallet.botWalletId(), resumed);
            audit.person(
                    person.tenantId(),
                    person.userId(),
                    "REAL_RESUMED",
                    "bot-wallet:" + wallet.botWalletId(),
                    Map.of(),
                    null,
                    null);
        });
        return view(person);
    }

    // ----- F4 and F5: reading and withdrawal -----

    public BotWalletView view(Principal person) {
        BotWalletAccount wallet = repository
                .activeWallet(person.tenantId(), person.userId())
                .orElseThrow(() -> new BotWalletException(BotWalletError.NOT_FOUND));
        // U5 (BR3.1, BR2.5): the global pauses (kill switch REAL, restore) show in the view of every person too.
        Preferences preferences = repository.preferences(wallet.botWalletId());
        return BotWalletViews.of(
                wallet,
                preferences,
                ledger.balance(person.tenantId(), wallet.botWalletId()),
                clock.instant(),
                globalPauses.active().keySet(),
                extras(person, wallet, preferences));
    }

    /** 261113-trading-real-bot U6 (rules.md "Mudança de contrato" item 2): who set the cap and the release, read only. */
    private BotWalletViews.Extras extras(Principal person, BotWalletAccount wallet, Preferences preferences) {
        return new BotWalletViews.Extras(
                BotWalletViews.capSetBy(
                        preferences.capLamports(), repository.lastCapLoweredByAdmin(wallet.botWalletId())),
                BotWalletViews.releaseOf(
                        RealReleases.current(repository.releases(person.userId()), properties.network())));
    }

    /** BR4.2: no flag, kill switch, release, terms or pause blocks a withdrawal; BR4.8 common idempotency. */
    /** A withdrawal without a limit of use (the total withdrawal, and the callers of the U1 before the Step 31). */
    public static final Runnable NO_LIMIT = () -> {};

    public IdempotentResponse requestWithdrawal(Principal person, IdempotencyKey key, Long lamports, boolean all) {
        return requestWithdrawal(person, key, lamports, all, NO_LIMIT);
    }

    /**
     * @param admitNew runs only for a NEW request, inside the idempotency action and before any effect: the limit of
     *     use of the partial withdrawal (NFR1.9). A replay with the same key never runs it; when it throws, the key is
     *     released and nothing is stored under it.
     */
    public IdempotentResponse requestWithdrawal(
            Principal person, IdempotencyKey key, Long lamports, boolean all, Runnable admitNew) {
        if (all == (lamports != null)) {
            throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
        }
        String content = all ? "all" : "lamports:" + lamports;
        IdempotencyRequest request = new IdempotencyRequest(
                IdempotentOperation.REAL_WITHDRAWAL,
                IdempotencyScope.authenticated(person, IdempotentOperation.REAL_WITHDRAWAL),
                key,
                sha256(content));
        return transactions.execute(status -> idempotency.execute(request, () -> {
            admitNew.run();
            BotWalletAccount wallet = repository
                    .lockActiveWallet(person.tenantId(), person.userId())
                    .orElseThrow(() -> new BotWalletException(BotWalletError.BOT_WALLET_NOT_ACTIVE));
            Withdrawal withdrawal = reserve(person.tenantId(), person.userId(), wallet, key.value(), lamports, all);
            return IdempotentOutcome.of(IdempotentResponse.fresh(202, write(BotWalletViews.of(withdrawal))));
        }));
    }

    private Withdrawal reserve(
            UUID tenantId, UUID userId, BotWalletAccount wallet, UUID key, Long lamports, boolean all) {
        Instant now = clock.instant();
        Preferences preferences = repository.preferences(wallet.botWalletId());
        RealBalance balance = ledger.balance(tenantId, wallet.botWalletId());
        long fee = WithdrawalFees.calculate(
                properties.baseFeeLamports(), properties.computeUnitLimit(), properties.computeUnitPrice());
        WithdrawalLimits limits = new WithdrawalLimits(
                preferences.feeReserveLamports(),
                fee,
                properties.rentExemptMinimumLamports(),
                Optional.empty(),
                repository.hasAmbiguousWithdrawal(wallet.botWalletId()));
        // 261113-trading-real-bot U5 (BR2.4): with a divergence or the restore open, the smaller of the ledger and the
        // network limits the withdrawal; it is never blocked.
        WithdrawalLimit.Context context = new WithdrawalLimit.Context(
                reconciliations.openDivergence(wallet.botWalletId()).isPresent(),
                globalPauses.openRestoreMarker().isPresent(),
                reconciliations.lastChainLamports(wallet.botWalletId()));
        WithdrawalAmount amount = all
                ? WithdrawalLimit.total(balance, limits, context)
                : WithdrawalLimit.partial(balance, lamports, limits, context);
        WithdrawalTransition requested = Withdrawal.request(tenantId, wallet.botWalletId(), amount, key, now);
        repository.insertWithdrawal(requested.withdrawal());
        requested.entries().forEach(ledger::record);
        audit.person(
                tenantId,
                userId,
                "REAL_WITHDRAWAL_REQUESTED",
                "withdrawal:" + requested.withdrawal().withdrawalId(),
                Map.of(
                        "lamports",
                        Long.toString(amount.lamports()),
                        "kind",
                        amount.kind().name()),
                RealReferences.botWallet(wallet.publicAddress()),
                null);
        return requested.withdrawal();
    }

    // ----- 261113-trading-real-bot U4: the mode and the withdrawal of the liquidation (M1) -----

    /**
     * BR7.1: lowers the mode in the transaction of the caller (the cancellation of the unsent orders joins it); the
     * TxSigner gets LOWER_MODE after the commit and, when it is down, the api keeps the more restrictive mode anyway.
     */
    public OperationMode lowerMode(UUID tenantId, UUID userId, OperationMode mode) {
        Instant now = clock.instant();
        BotWalletAccount wallet = repository
                .activeWallet(tenantId, userId)
                .orElseThrow(() -> new BotWalletException(BotWalletError.BOT_WALLET_NOT_ACTIVE));
        Preferences current = repository.preferences(wallet.botWalletId());
        if (current.operationMode() == mode) {
            return mode;
        }
        repository.savePreferences(wallet.botWalletId(), current.lowerMode(mode, now));
        audit.person(
                tenantId,
                userId,
                "REAL_MODE_LOWERED",
                "bot-wallet:" + wallet.botWalletId(),
                Map.of("from", current.operationMode().name(), "to", mode.name()),
                null,
                null);
        Runnable atSigner = () -> {
            try {
                signer.lowerMode(UUID.randomUUID(), wallet.publicAddress(), mode.name());
            } catch (TxSignerPort.SignerUnavailableException | TxSignerPort.SignerRefusedException later) {
                org.slf4j.LoggerFactory.getLogger(BotWalletService.class)
                        .warn("LOWER_MODE not delivered to the TxSigner; the api applies the lower mode");
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            atSigner.run();
                        }
                    });
        } else {
            atSigner.run();
        }
        return mode;
    }

    /**
     * BR7.2: raises the mode with the RAISE_MODE consent of the main wallet, checked here and again by the TxSigner. The
     * route that opens risk needs the REAL on and the release in force.
     */
    public OperationMode raiseMode(Principal person, OperationMode mode, UUID challengeId, String signature) {
        UUID tenantId = person.tenantId();
        UUID userId = person.userId();
        Instant now = clock.instant();
        requireRiskOpening(person, now);
        BotWalletAccount wallet = activeWallet(person);
        Preferences current = repository.preferences(wallet.botWalletId());
        if (challengeId == null || signature == null || !mode.isMorePermissiveThan(current.operationMode())) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        ConsentChallenge locked = transactions.execute(status -> {
            ConsentChallenge challenge = challenge(person, challengeId, ConsentKind.RAISE_MODE);
            if (challenge.mode() != mode) {
                throw new BotWalletException(BotWalletError.CONSENT_INVALID);
            }
            ConsentChallenge inUse = challenge.lock(now);
            repository.updateChallenge(inUse);
            return inUse;
        });
        if (!identity.verifyConsentSignature(wallet.mainWalletAddress(), locked.message(), signature)) {
            throw refuse(person, locked, BotWalletError.CONSENT_INVALID, "BAD_SIGNATURE");
        }
        try {
            signer.raise(
                    locked.challengeId(), wallet.publicAddress(), ConsentKind.RAISE_MODE, locked.message(), signature);
        } catch (TxSignerPort.SignerUnavailableException down) {
            transactions.executeWithoutResult(status -> repository.updateChallenge(locked.release()));
            throw new BotWalletException(BotWalletError.UPSTREAM_UNAVAILABLE);
        } catch (TxSignerPort.SignerRefusedException refused) {
            throw refuse(person, locked, errorOfSigner(refused.code()), refused.code());
        }
        transactions.executeWithoutResult(status -> {
            repository.updateChallenge(locked.consume(now));
            repository.useNonce(locked.nonce(), locked.expiresAt().plus(NONCE_RETENTION));
            repository.savePreferences(wallet.botWalletId(), current.raiseMode(mode, now));
            audit.person(
                    tenantId,
                    userId,
                    "REAL_MODE_RAISED",
                    "bot-wallet:" + wallet.botWalletId(),
                    Map.of("from", current.operationMode().name(), "to", mode.name()),
                    null,
                    null);
        });
        return mode;
    }

    /**
     * BR8.2: the withdrawal of everything free to the registered main wallet for the liquidation, idempotent by
     * {@code key} (the liquidationId). Empty when nothing is free. Never blocked by the flag, a pause or the kill switch.
     */
    public Optional<UUID> requestWithdrawalForLiquidation(UUID tenantId, UUID userId, UUID key) {
        return transactions.execute(status -> {
            Optional<UUID> existing = repository.withdrawalIdByKey(tenantId, key);
            if (existing.isPresent()) {
                return existing;
            }
            BotWalletAccount wallet = repository
                    .lockActiveWallet(tenantId, userId)
                    .orElseThrow(() -> new BotWalletException(BotWalletError.BOT_WALLET_NOT_ACTIVE));
            try {
                return Optional.of(
                        reserve(tenantId, userId, wallet, key, null, true).withdrawalId());
            } catch (BotWalletException nothing) {
                if (nothing.error() == BotWalletError.INSUFFICIENT_FREE_BALANCE) {
                    return Optional.empty();
                }
                throw nothing;
            }
        });
    }

    public BotWalletViews.WithdrawalView withdrawal(Principal person, UUID withdrawalId) {
        BotWalletAccount wallet = activeWallet(person);
        return repository
                .withdrawal(person.tenantId(), wallet.botWalletId(), withdrawalId)
                .map(BotWalletViews::of)
                .orElseThrow(() -> new BotWalletException(BotWalletError.NOT_FOUND));
    }

    public BotWalletViews.Page<BotWalletViews.WithdrawalView> withdrawals(Principal person) {
        BotWalletAccount wallet = activeWallet(person);
        List<BotWalletViews.WithdrawalView> items =
                repository.withdrawals(person.tenantId(), wallet.botWalletId(), PAGE).stream()
                        .map(BotWalletViews::of)
                        .toList();
        return new BotWalletViews.Page<>(items, null, items.size());
    }

    public BotWalletViews.Page<BotWalletViews.DepositView> deposits(Principal person) {
        BotWalletAccount wallet = activeWallet(person);
        List<BotWalletViews.DepositView> items =
                repository.deposits(person.tenantId(), wallet.botWalletId(), PAGE).stream()
                        .map(BotWalletViews::of)
                        .toList();
        return new BotWalletViews.Page<>(items, null, items.size());
    }

    /** Changes requested on {@code PATCH /api/v1/real/bot-wallet/preferences}. */
    public record PreferencesChange(
            Long capLamports, Integer maxSlippageBps, Boolean paused, UUID challengeId, String signature) {}

    // ----- helpers -----

    private void requireActivationGate(Principal person, boolean hasActiveWallet, Instant now) {
        ActivationGate.check(
                        properties.enabled(),
                        properties.enabled() && identity.hasAcceptedRealTerms(person.tenantId(), person.userId()),
                        properties.enabled() && released(person, now),
                        hasActiveWallet)
                .ifPresent(error -> {
                    throw new BotWalletException(error);
                });
    }

    private void requireRiskOpening(Principal person, Instant now) {
        if (!properties.enabled()) {
            throw new BotWalletException(BotWalletError.REAL_DISABLED);
        }
        if (!released(person, now)) {
            throw new BotWalletException(BotWalletError.REAL_NOT_RELEASED);
        }
    }

    private boolean released(Principal person, Instant now) {
        return RealReleases.isEffective(repository.releases(person.userId()), properties.network(), now);
    }

    private BotWalletAccount activeWallet(Principal person) {
        return repository
                .activeWallet(person.tenantId(), person.userId())
                .orElseThrow(() -> new BotWalletException(BotWalletError.BOT_WALLET_NOT_ACTIVE));
    }

    private ConsentChallenge challenge(Principal person, UUID challengeId, ConsentKind kind) {
        ConsentChallenge challenge = repository
                .challenge(person.tenantId(), person.userId(), challengeId)
                .orElseThrow(() -> new BotWalletException(BotWalletError.NOT_FOUND));
        if (challenge.kind() != kind) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        return challenge;
    }

    private String mainWallet(Principal person) {
        return identity.mainWallet(person.tenantId(), person.userId())
                .orElseThrow(() -> new BotWalletException(BotWalletError.NOT_FOUND));
    }

    /** BR1.5: a refused consent consumes its challenge and is audited. */
    private BotWalletException refuse(
            Principal person, ConsentChallenge challenge, BotWalletError error, String reason) {
        transactions.executeWithoutResult(status -> {
            repository.updateChallenge(challenge.consume(clock.instant()));
            audit.person(
                    person.tenantId(),
                    person.userId(),
                    "REAL_CONSENT_REFUSED",
                    "challenge:" + challenge.challengeId(),
                    Map.of("kind", challenge.kind().name(), "reason", reason),
                    null,
                    null);
        });
        return new BotWalletException(error);
    }

    private static BotWalletError errorOfSigner(String code) {
        return switch (code) {
            case "CONSENT_EXPIRED" -> BotWalletError.CONSENT_EXPIRED;
            case "REAL_DISABLED" -> BotWalletError.REAL_DISABLED;
            default -> BotWalletError.CONSENT_INVALID;
        };
    }

    private AcceptedConsent accepted(ConsentChallenge challenge, Instant now) {
        return new AcceptedConsent(
                UUID.randomUUID(),
                challenge.kind(),
                challenge.nonce(),
                challenge.expiresAt(),
                challenge.mode(),
                challenge.capLamports(),
                sha256(challenge.message()),
                now);
    }

    private static String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        bytes[0] = (byte) (bytes[0] | 1);
        return Base58Codec.encode(bytes);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A withdrawal answer could not be written", failure);
        }
    }

    /** Empty balance of a wallet just created. */
    static final class RealBalances {
        static final RealBalance EMPTY = new RealBalance(0, 0, 0, java.util.Set.of(), false);

        private RealBalances() {}
    }
}
