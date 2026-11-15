package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.CodecException;
import com.memetrade.txsigner.codec.SolanaTransaction;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.policy.PolicyDecision;
import com.memetrade.txsigner.policy.PolicyRefusal;
import com.memetrade.txsigner.policy.SwapCheck;
import com.memetrade.txsigner.policy.SwapMode;
import com.memetrade.txsigner.policy.SwapPolicy;
import com.memetrade.txsigner.policy.WithdrawalCheck;
import com.memetrade.txsigner.policy.WithdrawalPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;

/**
 * Signing of transactions (BR5.1, BR5.2, NFR1.4, NFR1.11). The KMS is called only after the content policy approved,
 * with the main wallet and the fee ceiling of the TxSigner's OWN policy, never the request's. Every decision is
 * recorded once per signingRequestId; a repetition with the same message returns the same answer, with another message
 * it is refused (REQUEST_MISMATCH). With REAL off only WITHDRAWAL is signed. Technical failures (KMS outage, recovery
 * wait) record nothing, so the same request can be retried.
 */
public final class SigningService {

    private final SignerSettings settings;
    private final Clock clock;
    private final KmsPort kms;
    private final SignerStore store;
    private final SignerEvents events;
    private final RecoveryGate recovery;
    private final WithdrawalPolicy withdrawalPolicy;
    private final SwapPolicy swapPolicy;
    private final Ed25519Verifier verifier = new Ed25519Verifier();

    public SigningService(
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
        this.withdrawalPolicy =
                new WithdrawalPolicy(settings.maxWithdrawalComputeUnits(), settings.baseFeeLamportsPerSignature());
        this.swapPolicy = new SwapPolicy(settings.maxSwapPriorityFeeLamports(), settings.baseFeeLamportsPerSignature());
    }

    public SigningOutcome sign(SignCommand command) {
        String digest = digestOf(command.transactionBase64());
        Optional<SigningRecord> existing = store.findSigningRecord(command.signingRequestId());
        if (existing.isPresent()) {
            return replay(existing.get(), command, digest);
        }
        if (recovery.phase() == RecoveryPhase.WAITING) {
            return SigningOutcome.refused(PolicyRefusal.RECOVERY_IN_PROGRESS);
        }
        if (command.operationKind() == OperationKind.SWAP) {
            return signSwap(command, digest);
        }
        if (command.operationKind() != OperationKind.WITHDRAWAL) {
            PolicyRefusal refusal =
                    settings.realEnabled() ? PolicyRefusal.OPERATION_NOT_SUPPORTED : PolicyRefusal.REAL_DISABLED;
            return refuse(command, digest, refusal);
        }
        Optional<WalletPolicy> policy = store.findPolicy(command.botWalletAddress());
        if (policy.isEmpty()) {
            return refuse(command, digest, PolicyRefusal.WALLET_NOT_FOUND);
        }
        if (policy.get().blocked()) {
            return refuse(command, digest, PolicyRefusal.WALLET_BLOCKED);
        }
        PolicyDecision decision = withdrawalPolicy.evaluate(new WithdrawalCheck(
                command.transactionBase64(),
                policy.get().botWalletAddress(),
                policy.get().mainWalletAddress(),
                command.context(),
                policy.get().maxWithdrawalFeeLamports()));
        if (!decision.approved()) {
            return refuse(command, digest, decision.refusal());
        }
        return signApproved(command, digest, policy.get(), decision.transaction());
    }

    /**
     * U4 (BR4.1, BR4.2, BR4.3): a SWAP only with the REAL on, for a wallet that is neither blocked nor closed, and only
     * inside the swap policy, with the cap and the mode of the TxSigner's OWN policy. The bot wallet signs in its slot:
     * the first in AUTO_FULL, the second in SIGNED (after the main wallet, which pays the fee).
     */
    private SigningOutcome signSwap(SignCommand command, String digest) {
        if (!settings.realEnabled()) {
            return refuse(command, digest, PolicyRefusal.REAL_DISABLED);
        }
        Optional<WalletPolicy> policy = store.findPolicy(command.botWalletAddress());
        if (policy.isEmpty()) {
            return refuse(command, digest, PolicyRefusal.WALLET_NOT_FOUND);
        }
        if (policy.get().blocked() || policy.get().closed()) {
            return refuse(command, digest, PolicyRefusal.WALLET_BLOCKED);
        }
        PolicyDecision decision = swapPolicy.evaluate(new SwapCheck(
                command.transactionBase64(),
                policy.get().botWalletAddress(),
                policy.get().mainWalletAddress(),
                command.swap(),
                policy.get().authorizedCapLamports(),
                policy.get().authorizedMode()));
        if (!decision.approved()) {
            return refuse(command, digest, decision.refusal());
        }
        int slot = command.swap().mode() == SwapMode.SIGNED ? 1 : 0;
        return signApproved(command, digest, policy.get(), decision.transaction(), slot);
    }

    private SigningOutcome signApproved(
            SignCommand command, String digest, WalletPolicy policy, SolanaTransaction transaction) {
        return signApproved(command, digest, policy, transaction, 0);
    }

    /**
     * Signs in the slot of the bot wallet. The signature of the record is the id of the transaction: the FIRST
     * signature, the one of the fee payer (the bot wallet in AUTO_FULL and in a withdrawal, the main wallet in SIGNED).
     */
    private SigningOutcome signApproved(
            SignCommand command, String digest, WalletPolicy policy, SolanaTransaction transaction, int slot) {
        byte[] message = transaction.messageBytes();
        byte[] signature = kms.sign(policy.kmsKeyRef(), message);
        if (!verifier.verify(Base58.decode(policy.botWalletAddress()), message, signature)) {
            throw new KmsUnavailableException("The KMS signature does not verify against the bot wallet");
        }
        SolanaTransaction signed = transaction.withSignature(slot, signature);
        SigningRecord record = new SigningRecord(
                command.signingRequestId(),
                policy.botWalletAddress(),
                command.operationKind(),
                digest,
                SigningRecord.Decision.SIGNED,
                null,
                Base58.encode(signed.signatures().get(0)),
                signed.serializeBase64(),
                clock.instant());
        return persist(record, command, digest);
    }

    private SigningOutcome refuse(SignCommand command, String digest, PolicyRefusal refusal) {
        SigningRecord record = new SigningRecord(
                command.signingRequestId(),
                command.botWalletAddress(),
                command.operationKind(),
                digest,
                SigningRecord.Decision.REFUSED,
                refusal.name(),
                null,
                null,
                clock.instant());
        return persist(record, command, digest);
    }

    private SigningOutcome persist(SigningRecord record, SignCommand command, String digest) {
        if (!store.insertSigningRecord(record)) {
            SigningRecord winner = store.findSigningRecord(record.signingRequestId())
                    .orElseThrow(() -> new IllegalStateException("Signing record vanished after a conflict"));
            return replay(winner, command, digest);
        }
        boolean signed = record.decision() == SigningRecord.Decision.SIGNED;
        events.publish(new SignerEvent(
                signed ? SignerEvent.Type.SIGNED : SignerEvent.Type.SIGNING_REFUSED,
                record.botWalletAddress(),
                record.signingRequestId(),
                record.operationKind().name(),
                record.refusalCode(),
                record.decidedAt()));
        return outcomeOf(record);
    }

    private SigningOutcome replay(SigningRecord record, SignCommand command, String digest) {
        if (!record.messageDigest().equals(digest)
                || !record.botWalletAddress().equals(command.botWalletAddress())
                || record.operationKind() != command.operationKind()) {
            events.publish(new SignerEvent(
                    SignerEvent.Type.REQUEST_MISMATCH,
                    record.botWalletAddress(),
                    record.signingRequestId(),
                    command.operationKind().name(),
                    PolicyRefusal.REQUEST_MISMATCH.name(),
                    clock.instant()));
            return SigningOutcome.refused(PolicyRefusal.REQUEST_MISMATCH);
        }
        return outcomeOf(record);
    }

    private static SigningOutcome outcomeOf(SigningRecord record) {
        if (record.decision() == SigningRecord.Decision.SIGNED) {
            return SigningOutcome.signed(record.signature(), record.signedTransactionBase64());
        }
        return SigningOutcome.refused(PolicyRefusal.valueOf(record.refusalCode()));
    }

    /** SHA-256 of the message bytes; of the raw text when the transaction does not even decode. */
    static String digestOf(String transactionBase64) {
        try {
            return Digests.sha256Hex(
                    SolanaTransaction.decodeBase64(transactionBase64).messageBytes());
        } catch (CodecException malformed) {
            String text = transactionBase64 == null ? "" : transactionBase64;
            return Digests.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
