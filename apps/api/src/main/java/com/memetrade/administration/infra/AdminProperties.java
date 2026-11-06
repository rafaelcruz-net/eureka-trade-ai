package com.memetrade.administration.infra;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixed settings of the kill switch of U8 ({@code memetrade.admin.kill-switch}, infrastructure-specification.md). They
 * live only in {@code application.yml}; the origin of the administrator app ({@code memetrade.admin.app-origin}) is read
 * by the CORS policy and the administrator entry of the identity module.
 *
 * @param killSwitch the apply lock, the attempts and the reconciliation (reliability-design.md)
 */
@ConfigurationProperties(prefix = "memetrade.admin")
public record AdminProperties(KillSwitch killSwitch) {

    public AdminProperties {
        killSwitch = killSwitch == null ? KillSwitch.defaults() : killSwitch;
    }

    /**
     * @param reconcileInterval delay between two reconciliation rounds (30 s, NFR10.1)
     * @param applyLockWait single budget of the apply lock (10 s, NFR2.3)
     * @param applyLockPoll pause between two {@code pg_try_advisory_lock} tries (200 ms)
     * @param applyAttempts calls of U5 per apply while bots fail, and re-reads while the version changes (3, BR2.3)
     * @param reconcileStaleAfter {@code reconcileNeeded} older than this is an alert (60 s, NFR9.3)
     * @param pendingStaleAfter {@code pendingBots > 0} older than this is an alert (2 min, NFR9.3)
     * @param lockAtMostFor ShedLock upper bound of one reconciliation round
     */
    public record KillSwitch(
            Duration reconcileInterval,
            Duration applyLockWait,
            Duration applyLockPoll,
            int applyAttempts,
            Duration reconcileStaleAfter,
            Duration pendingStaleAfter,
            Duration lockAtMostFor) {

        public KillSwitch {
            reconcileInterval = positive(reconcileInterval, "reconcile-interval", Duration.ofSeconds(30));
            applyLockWait = positive(applyLockWait, "apply-lock-wait", Duration.ofSeconds(10));
            applyLockPoll = positive(applyLockPoll, "apply-lock-poll", Duration.ofMillis(200));
            reconcileStaleAfter = positive(reconcileStaleAfter, "reconcile-stale-after", Duration.ofSeconds(60));
            pendingStaleAfter = positive(pendingStaleAfter, "pending-stale-after", Duration.ofMinutes(2));
            lockAtMostFor = positive(lockAtMostFor, "lock-at-most-for", Duration.ofMinutes(5));
            if (applyAttempts == 0) {
                applyAttempts = 3;
            }
            if (applyAttempts < 1 || applyAttempts > 10) {
                throw new IllegalArgumentException("memetrade.admin.kill-switch.apply-attempts must be 1 to 10");
            }
            if (applyLockPoll.compareTo(applyLockWait) > 0) {
                throw new IllegalArgumentException(
                        "memetrade.admin.kill-switch.apply-lock-poll must not exceed apply-lock-wait");
            }
        }

        public static KillSwitch defaults() {
            return new KillSwitch(null, null, null, 0, null, null, null);
        }
    }

    static Duration positive(Duration value, String name, Duration fallback) {
        Duration chosen = value == null ? fallback : value;
        Objects.requireNonNull(chosen, name);
        if (chosen.isNegative() || chosen.isZero()) {
            throw new IllegalArgumentException("memetrade.admin.kill-switch." + name + " must be positive");
        }
        return chosen;
    }
}
