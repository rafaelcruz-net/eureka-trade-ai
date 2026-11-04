package com.memetrade.identity.domain.invite;

import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsDocument;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * F4: the invitation lookup of the guest, without a session (BR2.1, BR2.3, NFR6.7). A malformed code is answered
 * exactly like an unknown one and never reaches the database. The answer carries only the derived state, the validity
 * and the current terms: never the tenant, the invitation id or anything about a person.
 */
public class InviteLookupService {

    private final InviteRepository invites;
    private final CurrentTerms terms;
    private final Clock clock;

    public InviteLookupService(InviteRepository invites, CurrentTerms terms, Clock clock) {
        this.invites = invites;
        this.terms = terms;
        this.clock = clock;
    }

    /** Empty for a malformed or unknown code (the same {@code 404 INVITE_NOT_FOUND} for both). */
    @Transactional(readOnly = true)
    public Optional<InviteLookup> lookup(String code) {
        if (!InviteCodes.isWellFormed(code)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return invites.findByDigest(InviteCodes.digest(code))
                .map(invite -> new InviteLookup(invite.statusAt(now), invite.expiresAt(), terms.current()));
    }

    /** What the guest sees before connecting the wallet. */
    public record InviteLookup(InviteStatus status, Instant expiresAt, TermsDocument terms) {}
}
