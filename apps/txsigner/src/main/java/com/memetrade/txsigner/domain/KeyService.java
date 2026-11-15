package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.policy.ConsentCheck;
import com.memetrade.txsigner.policy.ConsentPurpose;
import com.memetrade.txsigner.policy.ConsentRefusedException;
import com.memetrade.txsigner.policy.ConsentVerifier;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.policy.VerifiedConsent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Creation of bot wallet keys and changes of their policies (BR1.1, BR1.6, BR1.8, BR1.10, BR2.2, BR5.3). The key is
 * created only with REAL on, outside the recovery, under the hourly limit and after the TxSigner itself checked the
 * consent; its tags carry the HMAC of the main wallet, never the address. The nonce is recorded in the same transaction
 * as the policy, and a repetition of the same requestId returns the same wallet without a second key.
 */
public final class KeyService {

    /** A used nonce is kept one day after the consent expired (BR1.8). */
    static final Duration NONCE_RETENTION = Duration.ofDays(1);

    private final SignerSettings settings;
    private final Clock clock;
    private final KmsPort kms;
    private final SignerStore store;
    private final SignerEvents events;
    private final RecoveryGate recovery;
    private final ConsentVerifier consents;

    public KeyService(
            SignerSettings settings,
            Clock clock,
            KmsPort kms,
            SignerStore store,
            SignerEvents events,
            RecoveryGate recovery) {
        this.settings = settings;
        this.clock = clock;
        this.kms = kms;
        this.store = store;
        this.events = events;
        this.recovery = recovery;
        this.consents = new ConsentVerifier(
                clock, new Ed25519Verifier(), settings.network(), settings.maxCapLamports(), store::isNonceUsed);
    }

    public CreatedWallet createWallet(CreateWalletCommand command) {
        if (!settings.realEnabled()) {
            throw refusedCreation(command, SignerError.REAL_DISABLED, null);
        }
        if (recovery.phase() != RecoveryPhase.NORMAL) {
            throw new SignerException(SignerError.RECOVERY_IN_PROGRESS, null);
        }
        Optional<CreatedWallet> replay = replay(command);
        if (replay.isPresent()) {
            return replay.get();
        }
        Instant now = clock.instant();
        if (store.countPoliciesCreatedSince(now.minus(Duration.ofHours(1))) >= settings.maxKeysPerHour()) {
            throw refusedCreation(command, SignerError.KEY_RATE_LIMITED, null);
        }
        VerifiedConsent consent = verify(
                ConsentCheck.activation(
                        command.consentMessage(), command.consentSignature(), command.mainWalletAddress()),
                command);
        String mac =
                HexFormat.of().formatHex(kms.mac(command.mainWalletAddress().getBytes(StandardCharsets.UTF_8)));
        String keyRef = kms.createKey(new KeyTags(mac, command.requestId().toString(), settings.network()));
        String address = Base58.encode(kms.publicKey(keyRef));
        WalletPolicy policy = new WalletPolicy(
                address,
                keyRef,
                command.mainWalletAddress(),
                consent.capLamports(),
                OperationMode.SIGNED,
                false,
                false,
                false,
                command.requestId(),
                settings.maxWithdrawalFeeLamports(),
                now,
                now);
        return switch (store.insertPolicy(
                policy, consent.nonce(), consent.expiresAt().plus(NONCE_RETENTION))) {
            case APPLIED -> {
                events.publish(event(SignerEvent.Type.WALLET_CREATED, address, command, null));
                yield new CreatedWallet(address);
            }
            case DUPLICATE_REQUEST ->
                replay(command)
                        .orElseThrow(() -> refusedCreation(
                                command, SignerError.REQUEST_MISMATCH, "creation raced with another request"));
            case NONCE_USED -> throw refusedCreation(command, SignerError.CONSENT_INVALID, "NONCE_USED");
        };
    }

    public PolicyView changePolicy(ChangePolicyCommand command) {
        Optional<PolicyChange> applied = store.findChange(command.requestId());
        if (applied.isPresent()) {
            return viewOfReplay(applied.get(), command);
        }
        WalletPolicy policy = store.findPolicy(command.botWalletAddress())
                .orElseThrow(() -> new SignerException(SignerError.WALLET_NOT_FOUND, null));
        Instant now = clock.instant();
        VerifiedConsent consent = command.kind().isRaise() ? verifyRaise(command, policy) : null;
        WalletPolicy updated = updated(command, policy, consent, now);
        PolicyChange change = new PolicyChange(
                command.requestId(),
                policy.botWalletAddress(),
                command.kind(),
                updated.authorizedCapLamports(),
                updated.authorizedMode(),
                consent == null ? null : consent.messageDigest(),
                now);
        String nonce = consent == null ? null : consent.nonce();
        Instant purgeAfter = consent == null ? null : consent.expiresAt().plus(NONCE_RETENTION);
        return switch (store.applyChange(change, updated, nonce, purgeAfter)) {
            case APPLIED -> {
                events.publish(new SignerEvent(
                        SignerEvent.Type.POLICY_CHANGED,
                        policy.botWalletAddress(),
                        command.requestId(),
                        command.kind().name(),
                        null,
                        now));
                yield view(change, updated.closed());
            }
            case DUPLICATE_REQUEST ->
                viewOfReplay(store.findChange(command.requestId()).orElseThrow(), command);
            case NONCE_USED -> throw refusedChange(command, SignerError.CONSENT_INVALID, "NONCE_USED");
        };
    }

    private VerifiedConsent verifyRaise(ChangePolicyCommand command, WalletPolicy policy) {
        if (!settings.realEnabled()) {
            throw refusedChange(command, SignerError.REAL_DISABLED, null);
        }
        if (recovery.phase() != RecoveryPhase.NORMAL) {
            throw new SignerException(SignerError.RECOVERY_IN_PROGRESS, null);
        }
        if (policy.blocked() || policy.closed()) {
            throw refusedChange(command, SignerError.CONSENT_INVALID, "POLICY_NOT_OPEN");
        }
        if (command.consentMessage() == null || command.consentSignature() == null) {
            throw new SignerException(SignerError.VALIDATION_FAILED, "consent missing");
        }
        ConsentPurpose purpose =
                command.kind() == PolicyChange.Kind.RAISE_CAP ? ConsentPurpose.RAISE_CAP : ConsentPurpose.RAISE_MODE;
        try {
            return consents.verify(ConsentCheck.change(
                    command.consentMessage(),
                    command.consentSignature(),
                    policy.mainWalletAddress(),
                    purpose,
                    policy.botWalletAddress()));
        } catch (ConsentRefusedException refused) {
            throw refusedChange(
                    command, consentError(refused), refused.refusal().name());
        }
    }

    private static WalletPolicy updated(
            ChangePolicyCommand command, WalletPolicy policy, VerifiedConsent consent, Instant now) {
        long cap = policy.authorizedCapLamports();
        OperationMode mode = policy.authorizedMode();
        return switch (command.kind()) {
            case RAISE_CAP -> policy.withLimits(consent.capLamports(), restrictive(mode, consent.mode()), now);
            case RAISE_MODE -> policy.withLimits(Math.min(cap, consent.capLamports()), consent.mode(), now);
            case LOWER_CAP -> {
                if (command.lowerCapLamports() == null || command.lowerCapLamports() < 0) {
                    throw new SignerException(SignerError.VALIDATION_FAILED, "lowerTo cap missing");
                }
                yield policy.withLimits(Math.min(cap, command.lowerCapLamports()), mode, now);
            }
            case LOWER_MODE -> {
                if (command.lowerMode() == null) {
                    throw new SignerException(SignerError.VALIDATION_FAILED, "lowerTo mode missing");
                }
                yield policy.withLimits(cap, restrictive(mode, command.lowerMode()), now);
            }
            case CLOSE -> policy.closedAt(now);
        };
    }

    private static OperationMode restrictive(OperationMode current, OperationMode requested) {
        return requested.isMoreRestrictiveThan(current) ? requested : current;
    }

    private Optional<CreatedWallet> replay(CreateWalletCommand command) {
        return store.findPolicyByCreationRequest(command.requestId()).map(existing -> {
            if (!existing.mainWalletAddress().equals(command.mainWalletAddress())) {
                throw refusedCreation(command, SignerError.REQUEST_MISMATCH, null);
            }
            return new CreatedWallet(existing.botWalletAddress());
        });
    }

    private PolicyView viewOfReplay(PolicyChange change, ChangePolicyCommand command) {
        if (!change.botWalletAddress().equals(command.botWalletAddress()) || change.kind() != command.kind()) {
            throw new SignerException(SignerError.REQUEST_MISMATCH, null);
        }
        return view(change, change.kind() == PolicyChange.Kind.CLOSE);
    }

    private static PolicyView view(PolicyChange change, boolean closed) {
        return new PolicyView(change.botWalletAddress(), change.capLamports(), change.mode(), closed);
    }

    private VerifiedConsent verify(ConsentCheck check, CreateWalletCommand command) {
        try {
            return consents.verify(check);
        } catch (ConsentRefusedException refused) {
            throw refusedCreation(
                    command, consentError(refused), refused.refusal().name());
        }
    }

    private static SignerError consentError(ConsentRefusedException refused) {
        return "CONSENT_EXPIRED".equals(refused.code()) ? SignerError.CONSENT_EXPIRED : SignerError.CONSENT_INVALID;
    }

    private SignerException refusedCreation(CreateWalletCommand command, SignerError error, String reason) {
        events.publish(
                event(SignerEvent.Type.WALLET_CREATION_REFUSED, null, command, reason == null ? error.name() : reason));
        return new SignerException(error, reason);
    }

    private SignerException refusedChange(ChangePolicyCommand command, SignerError error, String reason) {
        events.publish(new SignerEvent(
                SignerEvent.Type.POLICY_CHANGE_REFUSED,
                command.botWalletAddress(),
                command.requestId(),
                command.kind().name(),
                reason == null ? error.name() : reason,
                clock.instant()));
        return new SignerException(error, reason);
    }

    private SignerEvent event(SignerEvent.Type type, String address, CreateWalletCommand command, String reason) {
        return new SignerEvent(type, address, command.requestId(), "CREATE_WALLET", reason, clock.instant());
    }
}
