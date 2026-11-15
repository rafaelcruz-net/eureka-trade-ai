package com.memetrade.txsigner.web;

import com.memetrade.txsigner.domain.OperationKind;
import com.memetrade.txsigner.domain.PolicyChange;
import com.memetrade.txsigner.policy.OperationMode;
import com.memetrade.txsigner.policy.SwapMode;
import com.memetrade.txsigner.policy.SwapSide;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the internal routes R1. Sizes and alphabets are checked before any decoding (security-design.md
 * "Validação de entrada"); lamports travel as integer text, never as a JSON number (contract "Lamports").
 */
final class InternalDtos {

    static final String ADDRESS = "^[1-9A-HJ-NP-Za-km-z]{32,44}$";
    static final String SIGNATURE = "^[1-9A-HJ-NP-Za-km-z]{64,90}$";
    static final String LAMPORTS = "^[0-9]{1,20}$";

    private InternalDtos() {}

    record CreateWalletRequest(
            @NotNull UUID requestId,
            @NotNull @Pattern(regexp = ADDRESS) String mainWalletAddress,
            @NotNull @Size(max = 1024) String consentMessage,
            @NotNull @Pattern(regexp = SIGNATURE) String consentSignature) {}

    record CreateWalletResponse(String botWalletAddress) {}

    record LowerTo(
            OperationMode mode, @Pattern(regexp = LAMPORTS) String capLamports) {}

    record ConsentRequest(
            @NotNull UUID requestId,
            @NotNull PolicyChange.Kind kind,
            @Size(max = 1024) String consentMessage,
            @Pattern(regexp = SIGNATURE) String consentSignature,
            @Valid LowerTo lowerTo) {}

    record PolicyResponse(String botWalletAddress, String capLamports, OperationMode mode, boolean closed) {}

    /**
     * WITHDRAWAL: {@code inputLamports} and {@code feeLamports}. SWAP (addendum of the U4): {@code mode}, {@code side},
     * the input of its side, {@code quotedOut}, {@code minOut}, {@code maxSlippageBps} and {@code feeLamports}. Base
     * units fit a {@code long} (at most 19 digits).
     */
    record SignatureContext(
            @Pattern(regexp = LAMPORTS) String inputLamports,
            @Pattern(regexp = LAMPORTS) String feeLamports,
            @Pattern(regexp = "^[0-9]{1,19}$") String minOut,
            @Pattern(regexp = "^[0-9]{1,19}$") String quotedOut,
            Integer maxSlippageBps,
            SwapMode mode,
            SwapSide side,
            @Pattern(regexp = "^[0-9]{1,19}$") String inputBaseUnits,
            // U4 review (Minor 4): the token of the order; the route source and destination are bound to it.
            @Pattern(regexp = ADDRESS) String mint) {}

    record SignatureRequest(
            @NotNull UUID signingRequestId,
            @NotNull @Pattern(regexp = ADDRESS) String botWalletAddress,
            @NotNull OperationKind operationKind,
            @NotNull @Size(min = 4, max = 1700) String transactionBase64,
            @Valid SignatureContext context) {}

    record SignatureResponse(String signature, String signedTransactionBase64) {}

    record WalletItem(String botWalletAddress, UUID creationRequestId, Instant createdAt, String status) {}

    record WalletList(List<WalletItem> items) {}

    record SignedItem(
            UUID signingRequestId,
            String botWalletAddress,
            String signature,
            OperationKind operationKind,
            Instant decidedAt) {}

    record SignedList(List<SignedItem> items) {}
}
