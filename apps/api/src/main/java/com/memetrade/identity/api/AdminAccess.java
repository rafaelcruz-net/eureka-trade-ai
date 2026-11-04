package com.memetrade.identity.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Authorisation of administrative operations (C8, BR2.1, NFR6.1), used by the administrative route rule AND by each
 * administrative operation itself (defence in depth: a spelling of the path that slipped past the route rule still
 * meets this check in the controller).
 */
public interface AdminAccess {

    /**
     * The principal of the current request when it is an administrator.
     *
     * @throws UnauthenticatedException {@code 401} when the request has no authenticated session
     * @throws com.memetrade.shared.error.ApiException {@code 403 FORBIDDEN_ROLE} for any other role, after recording
     *     exactly one {@code REQUEST_FORBIDDEN}
     */
    Principal requireAdministrator(HttpServletRequest request);

    /**
     * U3 (C17): whether a principal handed to a module interface is an administrator, so the interface refuses any
     * other role itself without naming the role.
     */
    boolean isAdministrator(Principal principal);
}
