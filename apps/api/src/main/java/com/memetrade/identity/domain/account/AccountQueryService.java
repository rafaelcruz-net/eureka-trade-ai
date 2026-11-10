package com.memetrade.identity.domain.account;

import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsAcceptanceLookup;
import com.memetrade.identity.domain.terms.TermsDocument;
import com.memetrade.identity.domain.terms.TermsStatus;
import com.memetrade.shared.wallet.WalletAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * F8: data of the Account screen (BR5.1 to BR5.4). Person and tenant come from the authenticated session only; the
 * wallet leaves masked (4 + "…" + 4); the session end is its ABSOLUTE deadline; REAL mode is always unavailable.
 *
 * <p>u3-reacceptance (BR3.10, L2): the screen also receives {@link TermsStatus}: the most recent acceptance, the
 * effective current version and whether the person is up to date. {@code upToDate} is the decision of the
 * reacceptance lock (an acceptance of the current version exists), so the screen never offers the new acceptance to a
 * person the lock lets through, even when the current version went back to an earlier accepted one.
 */
public class AccountQueryService {

    /** BR5.3 / NFR6.12: the only value REAL mode ever has in the beta. */
    public static final String REAL_MODE_UNAVAILABLE = "UNAVAILABLE";

    private final AccountDetailsRepository accounts;
    private final CurrentTerms terms;
    private final TermsAcceptanceLookup acceptances;

    public AccountQueryService(
            AccountDetailsRepository accounts, CurrentTerms terms, TermsAcceptanceLookup acceptances) {
        this.accounts = accounts;
        this.terms = terms;
        this.acceptances = acceptances;
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> view(UUID userId, UUID tenantId, UUID sessionId) {
        return accounts.find(userId, tenantId, sessionId).map(details -> toView(details, userId, tenantId));
    }

    private AccountView toView(AccountDetailsRepository.AccountDetails details, UUID userId, UUID tenantId) {
        Optional<AcceptedTerms> accepted = Optional.ofNullable(details.termsVersion())
                .map(version -> new AcceptedTerms(
                        version, details.acceptedAt(), terms.find(version).orElse(null)));
        Optional<TermsStatus> status = accepted.map(latest -> status(latest, userId, tenantId));
        return new AccountView(
                WalletAddress.mask(details.walletAddress()),
                details.provider(),
                details.sessionAbsoluteExpiresAt(),
                accepted,
                status,
                REAL_MODE_UNAVAILABLE);
    }

    private TermsStatus status(AcceptedTerms latest, UUID userId, UUID tenantId) {
        String current = terms.current().version();
        boolean upToDate = latest.version().equals(current) || acceptances.hasAccepted(userId, tenantId, current);
        return new TermsStatus(latest.version(), current, latest.acceptedAt(), upToDate);
    }

    /**
     * The Account screen; never the full address. {@code terms} is empty only for an account without any acceptance
     * (an account seeded by a test fixture); the screen then offers the acceptance of the current version.
     */
    public record AccountView(
            String walletAddressMasked,
            String provider,
            Instant sessionExpiresAt,
            Optional<AcceptedTerms> acceptedTerms,
            Optional<TermsStatus> terms,
            String realMode) {}

    /** The accepted version, when (UTC) and its texts, so the screen shows what was accepted (BR5.2). */
    public record AcceptedTerms(String version, Instant acceptedAt, TermsDocument document) {}
}
