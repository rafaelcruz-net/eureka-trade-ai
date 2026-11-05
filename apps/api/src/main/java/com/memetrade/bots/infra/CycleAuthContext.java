package com.memetrade.bots.infra;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.shared.config.ProcessRole;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The production authentication context of the worker (C10, security-design.md): the principal of the OWNER of the bot
 * a job handles, built from the bot row (tenant, person, role {@code BETA_USER}) and bound to the job thread for the
 * duration of the work (BR2.10, BR3.6). It is the only {@link AuthContext} the worker uses ({@code @Primary}); the api
 * keeps the request context of the identity module. Outside {@link #as}, there is no principal: a job that forgets
 * to bind the owner fails with {@link UnauthenticatedException} instead of acting as someone else.
 */
@Component
@Primary
@ProcessRole(ProcessRole.Kind.WORKER)
public class CycleAuthContext implements AuthContext {

    private static final ThreadLocal<Principal> CYCLE = new ThreadLocal<>();

    /** Runs {@code work} as {@code owner}, restoring the previous binding afterwards. */
    public <T> T as(Principal owner, Supplier<T> work) {
        Principal previous = CYCLE.get();
        CYCLE.set(owner);
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

    public void run(Principal owner, Runnable work) {
        as(owner, () -> {
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
        return Optional.ofNullable(CYCLE.get());
    }
}
