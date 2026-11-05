package com.memetrade.bots.worker;

import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * NFR9.1 (observability-design.md): the health component {@code botCycles} of the worker. DOWN with
 * {@code NO_SLOT_COMPLETED} when no cycle finished for more than 10 minutes while there are ACTIVE bots; UP otherwise.
 * INFORMATIONAL: it stays out of the liveness and readiness groups (Q6), so a quiet market never restarts the worker.
 * Details carry counts and instants only.
 */
@Component("botCyclesHealthIndicator")
@ProcessRole(ProcessRole.Kind.WORKER)
public class BotCyclesHealthIndicator implements HealthIndicator {

    static final Duration SILENCE = Duration.ofMinutes(10);

    private final JdbcCycleRepository cycles;
    private final JdbcBotRepository bots;
    private final Clock clock;

    public BotCyclesHealthIndicator(JdbcCycleRepository cycles, JdbcBotRepository bots, Clock clock) {
        this.cycles = cycles;
        this.bots = bots;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Optional<Instant> last = cycles.lastFinished();
        int active = bots.activeBots().size();
        Instant now = clock.instant();
        boolean silent =
                last.map(at -> Duration.between(at, now).compareTo(SILENCE) > 0).orElse(true);
        Health.Builder health =
                active > 0 && silent ? Health.down().withDetail("reason", "NO_SLOT_COMPLETED") : Health.up();
        health.withDetail("activeBots", active);
        last.ifPresent(at -> health.withDetail("lastCycleFinishedAt", at.toString()));
        return health.build();
    }
}
