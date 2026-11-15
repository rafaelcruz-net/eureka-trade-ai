package com.memetrade.identity.domain.realterms;

import com.memetrade.identity.api.RealIdentity;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.identity.domain.terms.TermsAcceptanceLookup;
import java.util.Optional;
import java.util.UUID;

/** M9 of the identity: main wallet, acceptance of the REAL terms and consent signature (fails closed). */
public final class RealIdentityService implements RealIdentity {

    private final TermsAcceptanceLookup acceptances;
    private final MainWalletLookup wallets;
    private final Ed25519SignatureVerifier verifier;
    private final String requiredVersion;

    public RealIdentityService(
            TermsAcceptanceLookup acceptances,
            MainWalletLookup wallets,
            Ed25519SignatureVerifier verifier,
            String requiredVersion) {
        this.acceptances = acceptances;
        this.wallets = wallets;
        this.verifier = verifier;
        this.requiredVersion = requiredVersion;
    }

    @Override
    public Optional<String> mainWallet(UUID tenantId, UUID userId) {
        return wallets.mainWallet(tenantId, userId);
    }

    @Override
    public boolean hasAcceptedRealTerms(UUID tenantId, UUID userId) {
        return acceptances.hasAccepted(userId, tenantId, requiredVersion);
    }

    @Override
    public String requiredRealTermsVersion() {
        return requiredVersion;
    }

    @Override
    public boolean verifyConsentSignature(String mainWalletAddress, String message, String signatureBase58) {
        if (mainWalletAddress == null || message == null || signatureBase58 == null) {
            return false;
        }
        return verifier.verify(mainWalletAddress, message, signatureBase58) == Ed25519SignatureVerifier.Result.VALID;
    }
}
