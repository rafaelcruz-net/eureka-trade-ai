package com.memetrade.identity.infra;

import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.IdentityCounts;
import com.memetrade.identity.api.InvitePage;
import com.memetrade.identity.api.InviteView;
import com.memetrade.identity.api.IssuedInvite;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.TenantOption;
import com.memetrade.identity.domain.invite.InviteService;
import com.memetrade.identity.domain.invite.InviteService.AdminActor;
import com.memetrade.identity.domain.login.LoginSettings;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.shared.web.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * C17 implementation. Each operation first checks, through {@link AdminAccess}, that the principal handed in is an
 * administrator (defence in depth besides the administrative route rule, BR1.5): any other role is
 * {@code 403 FORBIDDEN_ROLE}, recorded once as {@code REQUEST_FORBIDDEN} by the error handler. The stored
 * {@code issuedBy} is the MASKED wallet of the configured administrator (entities.md); it never leaves the module.
 */
public class IdentityAdministrationImpl implements IdentityAdministration {

    private final InviteService invites;
    private final AdminAccess admins;
    private final LoginSettings settings;
    private final com.memetrade.identity.domain.invite.IdentityDirectory directory;

    public IdentityAdministrationImpl(
            InviteService invites,
            AdminAccess admins,
            LoginSettings settings,
            com.memetrade.identity.domain.invite.IdentityDirectory directory) {
        this.invites = invites;
        this.admins = admins;
        this.settings = settings;
        this.directory = directory;
    }

    @Override
    public IssuedInvite issue(UUID tenantId, Instant expiresAt, Principal admin) {
        return invites.issue(tenantId, expiresAt, actor(admin));
    }

    @Override
    public InviteView revoke(UUID inviteId, Principal admin) {
        return invites.revoke(inviteId, actor(admin));
    }

    @Override
    public InvitePage list(Optional<String> cursor, int limit, Principal admin) {
        requireAdministrator(admin);
        return invites.list(cursor, limit);
    }

    @Override
    public IdentityCounts counts(Principal admin) {
        requireAdministrator(admin);
        return invites.counts();
    }

    @Override
    public List<TenantOption> activeTenants(Principal admin) {
        requireAdministrator(admin);
        return invites.activeTenants();
    }

    @Override
    public java.util.Map<UUID, String> tenantNames(java.util.Collection<UUID> tenantIds, Principal admin) {
        requireAdministrator(admin);
        java.util.Map<UUID, String> names = new java.util.LinkedHashMap<>();
        tenantIds.stream()
                .distinct()
                .forEach(id -> directory.tenant(id).ifPresent(tenant -> names.put(id, tenant.name())));
        return names;
    }

    @Override
    public Optional<String> maskedAdministratorWallet(UUID adminUserId, Principal admin) {
        requireAdministrator(admin);
        return directory.walletOf(adminUserId).map(WalletAddress::mask);
    }

    private AdminActor actor(Principal admin) {
        requireAdministrator(admin);
        return new AdminActor(admin.userId(), issuedBy(admin), CorrelationId.current());
    }

    private void requireAdministrator(Principal principal) {
        if (principal == null || !admins.isAdministrator(principal)) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only an administrator can manage invitations.");
        }
    }

    /** Masked wallet of the single configured administrator; otherwise the administrator id prefix. */
    private String issuedBy(Principal admin) {
        Set<String> wallets = settings.adminWalletAddresses();
        if (wallets.size() == 1) {
            return WalletAddress.mask(wallets.iterator().next());
        }
        return "admin:" + admin.userId().toString().substring(0, 8);
    }
}
