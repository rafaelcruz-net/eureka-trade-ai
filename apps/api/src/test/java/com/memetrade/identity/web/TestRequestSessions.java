package com.memetrade.identity.web;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * TEST ONLY: runs code as if the authentication filter had bound {@code principal} to the request, to call module
 * interfaces that check the role themselves (for example {@code RiskWeightAdministration}) without an HTTP route.
 */
public final class TestRequestSessions {

    private TestRequestSessions() {}

    public static <T> T as(Principal principal, Supplier<T> work) {
        RequestAuthContext.bind(new AuthenticatedSession(principal, UUID.randomUUID()));
        try {
            return work.get();
        } finally {
            RequestAuthContext.clear();
        }
    }
}
