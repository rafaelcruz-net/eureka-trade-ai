package com.memetrade.identity.api;

import java.util.Objects;

/**
 * Result of an issuance (NFR6.3): the invitation and, apart from it, the code. The code is NEVER part of what the
 * idempotency mechanism stores; the route adds it to the HTTP answer only when the answer is not a replay, which is
 * the single time it ever leaves the api (BR1.3, BR1.8).
 */
public record IssuedInvite(InviteView invite, String code) {

    public IssuedInvite {
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(code, "code");
    }

    @Override
    public String toString() {
        // The code never reaches a log line.
        return "IssuedInvite[invite=" + invite.inviteId() + "]";
    }
}
