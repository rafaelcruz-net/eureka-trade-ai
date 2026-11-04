package com.memetrade.identity.web;

import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.ForbiddenRequestRecorder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single administrative authorisation component (BR2.1, NFR6.1): the only production class that references
 * {@link Role#ADMIN} (ArchUnit rule). Used by {@link AdminRouteFilter} for every request under {@code /api/v1/admin}
 * and, through {@link AdminAccess}, by every administrative operation. A refusal records exactly one
 * {@code REQUEST_FORBIDDEN}: when the filter refuses, the request never reaches the operation.
 */
@Component
public class AdminAuthorization implements AdminAccess {

    static final String REASON = "ADMIN_ROUTE";

    private final AuthContext auth;
    private final ForbiddenRequestRecorder recorder;

    public AdminAuthorization(AuthContext auth, ForbiddenRequestRecorder recorder) {
        this.auth = auth;
        this.recorder = recorder;
    }

    /** True only for an authenticated administrator. */
    public boolean isAdministrator(Optional<Principal> principal) {
        return principal.isPresent() && principal.get().role() == Role.ADMIN;
    }

    @Override
    public boolean isAdministrator(Principal principal) {
        return isAdministrator(Optional.ofNullable(principal));
    }

    /** Records the refusal once and returns the {@code 403 FORBIDDEN_ROLE} to answer. */
    public ApiException refuse(HttpServletRequest request) {
        recorder.record(request, ErrorCode.FORBIDDEN_ROLE, REASON);
        return new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only an administrator can use this route.");
    }

    /** U4: an administrator token on a guest route; recorded once as {@code REQUEST_FORBIDDEN}. */
    public ApiException refuseOnGuestRoute(HttpServletRequest request) {
        recorder.record(request, ErrorCode.FORBIDDEN_ROLE, GUEST_ROUTE_REASON);
        return new ApiException(
                ErrorCode.FORBIDDEN_ROLE, "The administrator token is valid only on administrative routes.");
    }

    static final String GUEST_ROUTE_REASON = "GUEST_ROUTE";

    @Override
    public Principal requireAdministrator(HttpServletRequest request) {
        Principal principal = auth.current();
        if (isAdministrator(Optional.of(principal))) {
            return principal;
        }
        throw refuse(request);
    }
}
