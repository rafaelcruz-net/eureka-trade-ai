package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.policy.OperationMode;

/**
 * {@code POST /internal/v1/recovery/wallets} (infrastructure-specification.md "Âncora sem o endereço"): the api sends
 * the main wallet of one bot wallet, which the TxSigner checks against the {@code mainWalletMac} tag of the key, and
 * the current lowered limits of the api, of which the TxSigner applies the more restrictive value.
 */
public record RestoreWalletCommand(
        String botWalletAddress, String mainWalletAddress, Long currentCapLamports, OperationMode currentMode) {}
