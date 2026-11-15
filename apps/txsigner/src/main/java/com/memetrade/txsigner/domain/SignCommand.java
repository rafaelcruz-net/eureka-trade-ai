package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.policy.SwapContext;
import com.memetrade.txsigner.policy.WithdrawalContext;
import java.util.UUID;

/**
 * {@code POST /internal/v1/signatures}: idempotent by signingRequestId (contract R1). A WITHDRAWAL carries its
 * {@code context}; a SWAP (addendum of the U4) carries its {@code swap} context.
 */
public record SignCommand(
        UUID signingRequestId,
        String botWalletAddress,
        OperationKind operationKind,
        String transactionBase64,
        WithdrawalContext context,
        SwapContext swap) {

    /** A command without a swap context (every command of the U1). */
    public SignCommand(
            UUID signingRequestId,
            String botWalletAddress,
            OperationKind operationKind,
            String transactionBase64,
            WithdrawalContext context) {
        this(signingRequestId, botWalletAddress, operationKind, transactionBase64, context, null);
    }

    /** A SWAP of the U4. */
    public static SignCommand swap(
            UUID signingRequestId, String botWalletAddress, String transactionBase64, SwapContext swap) {
        return new SignCommand(signingRequestId, botWalletAddress, OperationKind.SWAP, transactionBase64, null, swap);
    }
}
