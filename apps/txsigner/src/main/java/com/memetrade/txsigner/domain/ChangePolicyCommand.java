package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.policy.OperationMode;
import java.util.UUID;

/**
 * {@code POST /internal/v1/wallets/{addr}/consents}: a raise carries the signed consent, which the TxSigner checks
 * again; a lowering carries only the more restrictive value and needs no signature (BR2.2).
 */
public record ChangePolicyCommand(
        UUID requestId,
        String botWalletAddress,
        PolicyChange.Kind kind,
        String consentMessage,
        String consentSignature,
        OperationMode lowerMode,
        Long lowerCapLamports) {

    public static ChangePolicyCommand raise(
            UUID requestId, String botWalletAddress, PolicyChange.Kind kind, String message, String signature) {
        return new ChangePolicyCommand(requestId, botWalletAddress, kind, message, signature, null, null);
    }

    public static ChangePolicyCommand lowerCap(UUID requestId, String botWalletAddress, long capLamports) {
        return new ChangePolicyCommand(
                requestId, botWalletAddress, PolicyChange.Kind.LOWER_CAP, null, null, null, capLamports);
    }

    public static ChangePolicyCommand lowerMode(UUID requestId, String botWalletAddress, OperationMode mode) {
        return new ChangePolicyCommand(
                requestId, botWalletAddress, PolicyChange.Kind.LOWER_MODE, null, null, mode, null);
    }

    public static ChangePolicyCommand close(UUID requestId, String botWalletAddress) {
        return new ChangePolicyCommand(requestId, botWalletAddress, PolicyChange.Kind.CLOSE, null, null, null, null);
    }
}
