package com.memetrade.realexecution;

import com.memetrade.realexecution.infra.CrashPoints;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEST ONLY: emulates the death of the worker right after a recorded step (U4-NFR4.3). The point fires once: the step
 * before it is committed, nothing after it runs, and the next run of the job (with the lease expired) resumes from the
 * database alone.
 */
public final class FakeCrashPoints implements CrashPoints {

    private final Set<String> armed = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Runnable> hooks = new ConcurrentHashMap<>();

    public void crashAt(String point) {
        armed.add(point);
    }

    /** Runs {@code hook} once when the point is reached, without stopping the worker (another worker acts there). */
    public void onReach(String point, Runnable hook) {
        hooks.put(point, hook);
    }

    @Override
    public void reached(String point) {
        Runnable hook = hooks.remove(point);
        if (hook != null) {
            hook.run();
        }
        if (armed.remove(point)) {
            throw new SimulatedCrash(point);
        }
    }

    public void reset() {
        armed.clear();
        hooks.clear();
    }

    /** The worker died at this point. */
    public static final class SimulatedCrash extends RuntimeException {
        public SimulatedCrash(String point) {
            super("simulated crash at " + point);
        }
    }
}
