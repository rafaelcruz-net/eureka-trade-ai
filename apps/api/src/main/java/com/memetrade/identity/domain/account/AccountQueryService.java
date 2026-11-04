package com.memetrade.identity.domain.account;

import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsDocument;
import com.memetrade.shared.wallet.WalletAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * F8: data of the Account screen (BR5.1 to BR5.4). Person and tenant come from the authenticated session only; the
 * wallet leaves masked (4 + "…" + 4); the session end is its ABSOLUTE deadline; REAL mode is always unavailable.
 */
public class AccountQueryService {

    /** BR5.3 / NFR6.12: the only value REAL mode ever has in the beta. */
    public static final String REAL_MODE_UNAVAILABLE = "UNAVAILABLE";

    private final AccountDetailsRepository accounts;
    private final CurrentTerms terms;

    public AccountQueryService(AccountDetailsRepository accounts, CurrentTerms terms) {
        this.accounts = accounts;
        this.terms = terms;
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> view(UUID userId, UUID tenantId, UUID sessionId) {
        return accounts.find(userId, tenantId, sessionId).map(this::toView);
    }

    private AccountView toView(AccountDetailsRepository.AccountDetails details) {
        Optional<AcceptedTerms> accepted = Optional.ofNullable(details.termsVersion())
                .map(version -> new AcceptedTerms(
                        version, details.acceptedAt(), terms.find(version).orElse(null)));
        return new AccountView(
                WalletAddress.mask(details.walletAddress()),
                details.provider(),
                details.sessionAbsoluteExpiresAt(),
                accepted,
                REAL_MODE_UNAVAILABLE);
    }

    /** The Account screen; never the full address. */
    public record AccountView(
            String walletAddressMasked,
            String provider,
            Instant sessionExpiresAt,
            Optional<AcceptedTerms> acceptedTerms,
            String realMode) {}

    /** The accepted version, when (UTC) and its texts, so the screen shows what was accepted (BR5.2). */
    public record AcceptedTerms(String version, Instant acceptedAt, TermsDocument document) {}
}
