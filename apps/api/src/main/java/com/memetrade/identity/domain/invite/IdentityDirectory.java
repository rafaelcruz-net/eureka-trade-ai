package com.memetrade.identity.domain.invite;

import com.memetrade.identity.api.TenantOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read port over tenants and accounts of the identity module, for invitations and the identity counts. */
public interface IdentityDirectory {

    Optional<TenantState> tenant(UUID tenantId);

    /** ACTIVE tenants ordered by name (BR1.7: no tenant is created, edited or suspended through the api). */
    List<TenantOption> activeTenants();

    /** People with role BETA_USER and status ACTIVE (BR1.6). */
    long countActiveGuests();

    /** A tenant and whether it is ACTIVE. */
    record TenantState(UUID tenantId, String name, boolean active) {}
}
