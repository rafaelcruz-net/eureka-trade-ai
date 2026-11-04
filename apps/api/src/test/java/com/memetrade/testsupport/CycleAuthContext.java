package com.memetrade.testsupport;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.web.RequestAuthContext;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Test double of the cycle authentication context (C10) that U5 will provide: a principal bound to the current thread
 * (the bot cycle), falling back to the HTTP request principal of {@link RequestAuthContext}. U5 does not exist yet,
 * so the U7 integration tests open the cycle themselves and bind its person here, never through the request body.
 */
public final class CycleAuthContext implements AuthContext {

    private static final ThreadLocal<Principal> CYCLE = new ThreadLocal<>();

    private final RequestAuthContext requests;

    public CycleAuthContext(RequestAuthContext requests) {
        this.requests = requests;
    }

    /** Runs {@code work} as {@code principal}, like a bot cycle of U5 for that person's bot. */
    public static <T> T as(Principal principal, Supplier<T> work) {
        Principal previous = CYCLE.get();
        CYCLE.set(principal);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CYCLE.remove();
            } else {
                CYCLE.set(previous);
            }
        }
    }

    public static void run(Principal principal, Runnable work) {
        as(principal, () -> {
            work.run();
            return null;
        });
    }

    @Override
    public Principal current() {
        return optional().orElseThrow(UnauthenticatedException::new);
    }

    @Override
    public Optional<Principal> optional() {
        Principal cycle = CYCLE.get();
        return cycle != null ? Optional.of(cycle) : requests.optional();
    }
}
