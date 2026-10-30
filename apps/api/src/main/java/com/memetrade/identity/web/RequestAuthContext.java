package com.memetrade.identity.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** C10 implementation for HTTP requests: the authentication filter binds the session to the request thread. */
@Component
public class RequestAuthContext implements AuthContext {

    private static final ThreadLocal<AuthenticatedSession> CURRENT = new ThreadLocal<>();

    static void bind(AuthenticatedSession session) {
        CURRENT.set(session);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** The authenticated session of this request (logout needs the session id). */
    public Optional<AuthenticatedSession> session() {
        return Optional.ofNullable(CURRENT.get());
    }

    @Override
    public Principal current() {
        return optional().orElseThrow(UnauthenticatedException::new);
    }

    @Override
    public Optional<Principal> optional() {
        return session().map(AuthenticatedSession::principal);
    }
}
