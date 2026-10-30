package com.memetrade.identity.domain.session;

import com.memetrade.identity.api.Role;
import java.util.UUID;

/** Account linked to a wallet, with the activity flags of the user and of the tenant. */
public record Account(UUID userId, UUID tenantId, Role role, boolean userActive, boolean tenantActive) {

    /** BR1.5: only an ACTIVE BETA_USER of an ACTIVE tenant signs in through the guest entry. */
    public boolean canEnterAsGuest() {
        return role == Role.BETA_USER && userActive && tenantActive;
    }
}
