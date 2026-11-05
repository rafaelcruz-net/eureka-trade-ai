package com.memetrade.ledger.loadtest;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.api.UnauthenticatedException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE (which starts only together with "test", {@code LoadtestProfileGuard}): the
 * authentication context (C10) of the load cycle driver, standing in for the cycle principal that U5 will provide.
 *
 * <p>It never creates a {@link Principal}. An HTTP request keeps the principal of its validated session (the request
 * context of the identity module, injected by its bean name); the first time a BETA_USER person is seen on a request,
 * that same principal is remembered, and the driver later binds it on its own threads to run that person's bots, the
 * way U5 will bind the owner of a bot. Tenant and person therefore still come only from a session (NFR6.1, BR9.1).
 */
@Component
@Primary
@Profile("loadtest")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoadtestCycleAuthContext implements AuthContext {

    private static final ThreadLocal<Principal> CYCLE = new ThreadLocal<>();

    private final AuthContext requests;
    private final Map<java.util.UUID, Principal> people = new ConcurrentHashMap<>();
    private final java.util.Queue<Principal> order = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public LoadtestCycleAuthContext(@Qualifier("requestAuthContext") AuthContext requests) {
        this.requests = requests;
    }

    /** Runs {@code work} as {@code person}, like the cycle of one of that person's bots. */
    public <T> T as(Principal person, Supplier<T> work) {
        Principal previous = CYCLE.get();
        CYCLE.set(person);
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

    /** The BETA_USER people seen on authenticated requests, in the order they were first seen. */
    public List<Principal> people() {
        return List.copyOf(order);
    }

    @Override
    public Principal current() {
        return optional().orElseThrow(UnauthenticatedException::new);
    }

    @Override
    public Optional<Principal> optional() {
        Principal cycle = CYCLE.get();
        if (cycle != null) {
            return Optional.of(cycle);
        }
        Optional<Principal> request = requests.optional();
        request.filter(principal -> principal.role() == Role.BETA_USER).ifPresent(this::remember);
        return request;
    }

    private void remember(Principal person) {
        if (people.putIfAbsent(person.userId(), person) == null) {
            order.add(person);
        }
    }
}
