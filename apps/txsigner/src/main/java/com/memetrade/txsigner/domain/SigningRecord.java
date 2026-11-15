package com.memetrade.txsigner.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Record of one signing request, signed or refused (entities.md SigningRecord, BR5.2). The signed transaction is kept
 * so that a repetition of the same request returns the same answer without a second KMS call (contract R1
 * "Idempotência"); refusals keep only the reason code, never the transaction.
 */
public record SigningRecord(
        UUID signingRequestId,
        String botWalletAddress,
        OperationKind operationKind,
        String messageDigest,
        Decision decision,
        String refusalCode,
        String signature,
        String signedTransactionBase64,
        Instant decidedAt) {

    public enum Decision {
        SIGNED,
        REFUSED
    }
}
