package com.memetrade.testsupport;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/** ShedLock provider in memory for unit tests; {@link #hold(String)} simulates another instance holding a lock. */
public final class InMemoryLockProvider implements LockProvider {

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    public void hold(String name) {
        held.add(name);
    }

    public void release(String name) {
        held.remove(name);
    }

    @Override
    public Optional<SimpleLock> lock(LockConfiguration configuration) {
        String name = configuration.getName();
        if (!held.add(name)) {
            return Optional.empty();
        }
        return Optional.of(() -> held.remove(name));
    }
}
