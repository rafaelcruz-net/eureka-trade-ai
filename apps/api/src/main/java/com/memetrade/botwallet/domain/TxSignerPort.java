package com.memetrade.botwallet.domain;

import java.util.UUID;

/**
 * Client port of the TxSigner (contract R1 and its addendum: {@code feeLamports}, reason codes, recovery routes). The
 * API and the worker never sign: they ask the TxSigner, which checks everything again and only restricts.
 */
public interface TxSignerPort {

    /** Creates the key and the policy; idempotent by requestId (the challengeId). Returns the bot wallet address. */
    String createWallet(UUID requestId, String mainWalletAddress, String consentMessage, String consentSignature);

    /** LOWER_CAP: only restricts, no signature. */
    void lowerCap(UUID requestId, String botWalletAddress, long capLamports);

    /** RAISE_CAP or RAISE_MODE with the signed consent, which the TxSigner checks again. */
    void raise(UUID requestId, String botWalletAddress, ConsentKind kind, String consentMessage, String signature);

    /** Signs a WITHDRAWAL with its context (amount and fee calculated before signing). */
    SignOutcome signWithdrawal(
            UUID signingRequestId, String botWalletAddress, String transactionBase64, long lamports, long feeLamports);

    /** 261113-trading-real-bot U4 (BR7.1): LOWER_MODE only restricts, no signature. */
    void lowerMode(UUID requestId, String botWalletAddress, String mode);

    /**
     * 261113-trading-real-bot U4 (BR4.1, BR4.2, contract R1 addendum): signs a SWAP with its context; idempotent by
     * {@code signingRequestId}. A refusal (4xx) comes back as the refusal code, never thrown.
     */
    SignOutcome signSwap(String botWalletAddress, com.memetrade.botwallet.api.SwapSigningRequest request);

    /** 261113-trading-real-bot U4 (BR9.1): the TxSigner answers an authenticated read. */
    boolean reachable();

    /** Result of a signing request: the signature and the signed transaction, or the refusal code. */
    record SignOutcome(String signature, String signedTransactionBase64, String refusal) {

        public boolean signed() {
            return refusal == null;
        }
    }

    /** The TxSigner did not answer or answered 5xx: retried later with the same request id. */
    class SignerUnavailableException extends RuntimeException {
        public SignerUnavailableException(String message) {
            super(message);
        }
    }

    /** The TxSigner refused (4xx): never retried as is; the code goes to the audit only. */
    class SignerRefusedException extends RuntimeException {
        private final String code;

        public SignerRefusedException(String code) {
            super("TxSigner refused: " + code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
