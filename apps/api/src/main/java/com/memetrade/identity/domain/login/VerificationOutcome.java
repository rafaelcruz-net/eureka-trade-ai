package com.memetrade.identity.domain.login;

import com.memetrade.identity.domain.session.OpenedSession;

/** Result of a verification; a refusal is an outcome of the operation, not a rollback (BR1.6). */
public sealed interface VerificationOutcome {

    record Accepted(OpenedSession session) implements VerificationOutcome {}

    record Rejected(RejectionReason reason) implements VerificationOutcome {

        /** The administrator wallet gets 403 FORBIDDEN_ROLE; every other refusal is 401. */
        public boolean forbiddenRole() {
            return reason == RejectionReason.ADMIN_WALLET;
        }
    }
}
