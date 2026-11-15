package com.memetrade.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Interface M9 of the identity for the REAL (contract-summary.md): the main wallet verified at login, the acceptance of
 * the REAL terms (v3, BR1.2) and the check of a consent signature with the native Ed25519 (BR1.5). The consent text is
 * built and compared by the BotWallet; this interface only says whether the main wallet signed exactly that text.
 */
public interface RealIdentity {

    Optional<String> mainWallet(UUID tenantId, UUID userId);

    boolean hasAcceptedRealTerms(UUID tenantId, UUID userId);

    /** The version of the terms the REAL activation requires. */
    String requiredRealTermsVersion();

    boolean verifyConsentSignature(String mainWalletAddress, String message, String signatureBase58);
}
