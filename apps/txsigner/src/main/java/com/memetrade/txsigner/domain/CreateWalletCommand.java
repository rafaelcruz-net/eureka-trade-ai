package com.memetrade.txsigner.domain;

import java.util.UUID;

/** {@code POST /internal/v1/wallets}: the requestId is the challengeId of the activation (BR1.10). */
public record CreateWalletCommand(
        UUID requestId, String mainWalletAddress, String consentMessage, String consentSignature) {}
