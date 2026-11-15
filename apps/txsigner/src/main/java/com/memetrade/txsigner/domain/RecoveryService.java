package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.policy.OperationMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recovery mode of the TxSigner after its database was restored (NFR4.13, security-design.md, infrastructure-
 * specification.md "Âncora sem o endereço"). For five minutes of wall clock (150 blocks; the TxSigner never talks to the
 * RPC) nothing is signed, so a signature lost in the restore has an expired blockhash; then only withdrawals until the
 * recovery completes. A policy is rebuilt only from the immutable key tags: the api sends the main wallet, which must
 * match the {@code mainWalletMac} tag through VerifyMac; the policy comes back with cap 0 and SIGNED, and only a new
 * consent widens it. A wrong network, MAC or anchor blocks the key and raises an alert. Withdrawals stay possible.
 */
public final class RecoveryService implements RecoveryGate {

    private final SignerSettings settings;
    private final Clock clock;
    private final KmsPort kms;
    private final SignerStore store;
    private final SignerEvents events;
    private final boolean enabled;
    private final Duration waitDuration;
    private final Map<String, String> keyRefByAddress = new ConcurrentHashMap<>();
    private final AtomicInteger restored = new AtomicInteger();
    private final AtomicInteger blocked = new AtomicInteger();
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile UUID runId;

    public RecoveryService(
            SignerSettings settings,
            Clock clock,
            KmsPort kms,
            SignerStore store,
            SignerEvents events,
            boolean enabled,
            Duration waitDuration) {
        this.settings = settings;
        this.clock = clock;
        this.kms = kms;
        this.store = store;
        this.events = events;
        this.enabled = enabled;
        this.waitDuration = waitDuration;
    }

    /** Starts a recovery run when the option is on (called once when the process is ready). */
    public void start() {
        if (enabled && startedAt == null) {
            startedAt = clock.instant();
            runId = UUID.randomUUID();
            store.startRecoveryRun(runId, startedAt);
        }
    }

    @Override
    public RecoveryPhase phase() {
        if (!enabled || completedAt != null || startedAt == null) {
            return RecoveryPhase.NORMAL;
        }
        return clock.instant().isBefore(startedAt.plus(waitDuration))
                ? RecoveryPhase.WAITING
                : RecoveryPhase.WITHDRAWALS_ONLY;
    }

    public UUID runId() {
        return runId;
    }

    /** Rebuilds or confirms the policy of one bot wallet with the main wallet sent by the api. */
    public PolicyView restore(RestoreWalletCommand command) {
        String keyRef = findKeyRef(command.botWalletAddress()).orElseThrow(() -> refuse("KEY_NOT_FOUND"));
        KeyTags tags = KeyTags.from(kms.tags(keyRef)).orElseThrow(() -> refuse("KEY_NOT_FOUND"));
        Optional<WalletPolicy> existing = store.findPolicy(command.botWalletAddress());
        if (!settings.network().equals(tags.network())) {
            throw block(command.botWalletAddress(), existing, "NETWORK_MISMATCH");
        }
        if (!macMatches(command.mainWalletAddress(), tags.mainWalletMac())) {
            throw block(command.botWalletAddress(), existing, "MAC_MISMATCH");
        }
        Instant now = clock.instant();
        if (existing.isPresent()) {
            WalletPolicy policy = existing.get();
            if (!policy.mainWalletAddress().equals(command.mainWalletAddress()) || policy.blocked()) {
                throw block(command.botWalletAddress(), existing, "ANCHOR_MISMATCH");
            }
            WalletPolicy lowered = policy.withLimits(
                    lower(policy.authorizedCapLamports(), command.currentCapLamports()),
                    restrictive(policy.authorizedMode(), command.currentMode()),
                    now);
            store.updatePolicy(lowered);
            return view(lowered);
        }
        WalletPolicy rebuilt = new WalletPolicy(
                command.botWalletAddress(),
                keyRef,
                command.mainWalletAddress(),
                0,
                OperationMode.SIGNED,
                false,
                false,
                true,
                UUID.fromString(tags.creationRequestId()),
                settings.maxWithdrawalFeeLamports(),
                now,
                now);
        if (store.insertRestoredPolicy(rebuilt) == SignerStore.WriteResult.APPLIED) {
            restored.incrementAndGet();
            events.publish(new SignerEvent(
                    SignerEvent.Type.WALLET_RESTORED, command.botWalletAddress(), null, "RECOVERY", null, now));
        }
        return view(store.findPolicy(command.botWalletAddress()).orElse(rebuilt));
    }

    /** Ends the recovery: writes recoveryCompletedAt; refused while the five minutes have not passed. */
    public void complete() {
        if (phase() == RecoveryPhase.WAITING) {
            throw new SignerException(SignerError.RECOVERY_IN_PROGRESS, "the wait of 150 blocks is not over");
        }
        if (!enabled || completedAt != null) {
            return;
        }
        Instant now = clock.instant();
        store.completeRecoveryRun(runId, now, restored.get(), blocked.get());
        completedAt = now;
        events.publish(new SignerEvent(SignerEvent.Type.RECOVERY_COMPLETED, null, runId, "RECOVERY", null, now));
    }

    private Optional<String> findKeyRef(String botWalletAddress) {
        String cached = keyRefByAddress.get(botWalletAddress);
        if (cached != null) {
            return Optional.of(cached);
        }
        for (String keyRef : kms.listKeyRefs()) {
            if (KeyTags.from(kms.tags(keyRef)).isPresent()) {
                keyRefByAddress.putIfAbsent(Base58.encode(kms.publicKey(keyRef)), keyRef);
            }
        }
        return Optional.ofNullable(keyRefByAddress.get(botWalletAddress));
    }

    private boolean macMatches(String mainWalletAddress, String macHex) {
        byte[] mac;
        try {
            mac = HexFormat.of().parseHex(macHex);
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return kms.verifyMac(mainWalletAddress.getBytes(StandardCharsets.UTF_8), mac);
    }

    private SignerException block(String botWalletAddress, Optional<WalletPolicy> existing, String reason) {
        Instant now = clock.instant();
        existing.filter(policy -> !policy.blocked()).ifPresent(policy -> {
            store.updatePolicy(policy.blockedAt(now));
            blocked.incrementAndGet();
        });
        events.publish(
                new SignerEvent(SignerEvent.Type.WALLET_BLOCKED, botWalletAddress, null, "RECOVERY", reason, now));
        return refuse(reason);
    }

    private static SignerException refuse(String reason) {
        return new SignerException(SignerError.RECOVERY_REFUSED, reason);
    }

    private static long lower(long current, Long requested) {
        return requested == null ? current : Math.min(current, requested);
    }

    private static OperationMode restrictive(OperationMode current, OperationMode requested) {
        return requested != null && requested.isMoreRestrictiveThan(current) ? requested : current;
    }

    private static PolicyView view(WalletPolicy policy) {
        return new PolicyView(
                policy.botWalletAddress(), policy.authorizedCapLamports(), policy.authorizedMode(), policy.closed());
    }
}
