package com.memetrade.botwallet.api;

import java.util.Optional;
import java.util.UUID;

/**
 * M1 for the REAL execution (261113-trading-real-bot U4, contract M1 {@code signSwap}, {@code recheckBeforeSend},
 * {@code applyConsent}, {@code lowerMode}, {@code requestWithdrawal}). Tenant and person always filter the wallet; a
 * wallet of another tenant is never read. The BotWallet repasses the swap to the TxSigner, which checks everything
 * again and only signs inside its policy (BR4.3); nothing here holds a key.
 */
public interface RealExecutionWallet {

    /** The ACTIVE wallet of the person with its two addresses and its preferences; empty when there is none. */
    Optional<RealWalletInfo> wallet(UUID tenantId, UUID userId);

    /**
     * BR3.3: before signing and before sending an order. Cancelled when the REAL is off, when the current mode is more
     * restrictive than the mode of the order, on a pause (a liquidation passes every pause but KILL_SWITCH and
     * DIVERGENCE, BR1.1 of the U3) or on the kill switch.
     *
     * @param liquidation the order is a sale of the "vender tudo e sacar"
     */
    Recheck recheckBeforeSend(UUID tenantId, UUID userId, RealOperationMode orderMode, boolean liquidation);

    /**
     * BR4.1, BR4.2: asks the TxSigner to sign the swap (contract R1 {@code operationKind: SWAP}); idempotent by
     * {@code signingRequestId} (the realOrderId). Called OUTSIDE a database transaction.
     *
     * @throws RealUpstreamUnavailable the TxSigner did not answer; the same request may be repeated
     */
    SwapSigning signSwap(UUID tenantId, UUID userId, SwapSigningRequest request);

    /** BR7.1: lowers the mode at once (repassed to the TxSigner as LOWER_MODE); returns the mode in force. */
    RealOperationMode lowerMode(UUID tenantId, UUID userId, RealOperationMode mode);

    /**
     * BR7.2: raises the mode with the RAISE_MODE consent of the main wallet, checked by the API and again by the
     * TxSigner. The person is the one of the session (never built outside the authentication path).
     *
     * @throws RealConsentRefused the consent does not check
     * @throws RealUpstreamUnavailable the TxSigner did not answer
     */
    RealOperationMode applyModeConsent(
            com.memetrade.identity.api.Principal person, RealOperationMode mode, UUID challengeId, String signature);

    /**
     * BR8.2: the withdrawal of everything free to the registered main wallet, idempotent by {@code idempotencyKey}
     * (the liquidationId). Empty when nothing is free to withdraw. Never blocked by the flag, a pause or the kill switch.
     */
    Optional<UUID> requestWithdrawalAll(UUID tenantId, UUID userId, String idempotencyKey);

    /** BR9.1: the call of health of the TxSigner (an authenticated read). */
    boolean signerReachable();

    /** The result of {@link #recheckBeforeSend}. */
    sealed interface Recheck permits Recheck.Allowed, Recheck.Cancelled {
        record Allowed() implements Recheck {}

        record Cancelled(RecheckReason reason) implements Recheck {}

        static Recheck allowed() {
            return new Allowed();
        }
    }

    /** Why the recheck cancels the order (the failure code of the order has the same name). */
    enum RecheckReason {
        REAL_DISABLED,
        MODE_LOWERED,
        PAUSED,
        KILL_SWITCH
    }

    /** The answer of the TxSigner: the signature and the signed transaction, or the refusal code. */
    sealed interface SwapSigning permits SwapSigning.Signed, SwapSigning.Refused {
        record Signed(String txSignature, String signedTransactionBase64) implements SwapSigning {}

        record Refused(String code) implements SwapSigning {}
    }
}
