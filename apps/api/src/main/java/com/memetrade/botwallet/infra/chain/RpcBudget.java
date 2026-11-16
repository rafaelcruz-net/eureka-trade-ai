package com.memetrade.botwallet.infra.chain;

import com.memetrade.botwallet.domain.ChainPort.Purpose;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Requests per second of each provider (rpcMaxRps, NFR3.7), split 80% for deposits and 20% for withdrawals, in windows
 * of one second of the injected clock. A request over budget goes to the other provider or waits for the next cycle.
 */
public class RpcBudget {

    private final Clock clock;
    private final int maxRps;
    private final Map<String, long[]> windows = new HashMap<>();

    public RpcBudget(Clock clock, int maxRps) {
        this.clock = clock;
        this.maxRps = maxRps;
    }

    public synchronized boolean tryAcquire(String provider, Purpose purpose) {
        long second = clock.millis() / 1000;
        long[] window = windows.computeIfAbsent(provider + ":" + purpose, k -> new long[] {second, 0});
        if (window[0] != second) {
            window[0] = second;
            window[1] = 0;
        }
        if (window[1] >= share(purpose)) {
            return false;
        }
        window[1]++;
        return true;
    }

    /**
     * The deposits keep 80% and the withdrawals (and the execution) 20% of {@code maxRps}. 261113-trading-real-bot U5
     * (performance-design.md): the reconciliation (20%) and the restore check (10%) have a quota of their own, on top of
     * those two, so they never take the budget of a withdrawal nor of an order.
     */
    int share(Purpose purpose) {
        int deposits = Math.max(1, maxRps * 80 / 100);
        return switch (purpose) {
            case DEPOSITS -> deposits;
            case WITHDRAWALS -> Math.max(1, maxRps - deposits);
            case RECONCILIATION -> Math.max(1, maxRps * 20 / 100);
            case RESTORE_CHECK -> Math.max(1, maxRps * 10 / 100);
        };
    }
}
