package com.memetrade.bots.worker;

import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U4 (decision F3 of the founder, NFR2.18; observability-design.md "Gauge de bots presos"): IN
 * MEMORY only, the number of unavailable reads in a row of each REAL bot, the base of the gauge
 * {@code real_own_holdings_stuck_bots}. An available read and BOT_NOT_OWNED remove the entry of the bot; BUDGET_EXCEEDED
 * and READ_FAILED add one; a cycle without a read changes nothing. A bot is stuck with 3 or more in a row seen in the
 * last 20 minutes. Entries older than 20 minutes leave at every update and every read of the gauge, and the map holds at
 * most 10,000 entries (the oldest leaves, with a WARN). Never stored, never a decision about money: a signal only.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class OwnHoldingsStreaks {

    static final int STUCK_AFTER = 3;
    static final Duration EVICT_AFTER = Duration.ofMinutes(20);
    static final int MAX_ENTRIES = 10_000;

    private static final Logger log = LoggerFactory.getLogger(OwnHoldingsStreaks.class);

    /** The unavailable reads in a row of one bot and when the last was seen. */
    record Streak(int failures, Instant lastSeen) {}

    private final Map<UUID, Streak> streaks = new ConcurrentHashMap<>();
    private final Clock clock;

    public OwnHoldingsStreaks(Clock clock) {
        this.clock = clock;
    }

    /** The answer of one read of the own holdings of {@code botId}. */
    public void update(UUID botId, HoldingsResult result) {
        Instant now = clock.instant();
        evict(now);
        switch (result) {
            case HoldingsResult.Available available -> streaks.remove(botId);
            case HoldingsResult.Unavailable unavailable -> {
                if (unavailable.reason() == UnavailableReason.BOT_NOT_OWNED) {
                    streaks.remove(botId);
                } else {
                    streaks.merge(
                            botId,
                            new Streak(1, now),
                            (before, one) -> new Streak(Math.addExact(before.failures(), 1), now));
                    bound();
                }
            }
        }
    }

    /** The unavailable reads in a row of {@code botId} (0 without an entry). */
    public int streak(UUID botId) {
        Streak streak = streaks.get(botId);
        return streak == null ? 0 : streak.failures();
    }

    /** The bots with {@value #STUCK_AFTER} or more unavailable reads in a row seen in the last 20 minutes. */
    public int stuckBots() {
        Instant now = clock.instant();
        evict(now);
        return (int) streaks.values().stream()
                .filter(streak -> streak.failures() >= STUCK_AFTER)
                .count();
    }

    /** How many bots the map holds. */
    public int entries() {
        return streaks.size();
    }

    private void evict(Instant now) {
        Instant oldest = now.minus(EVICT_AFTER);
        streaks.values().removeIf(streak -> streak.lastSeen().isBefore(oldest));
    }

    private void bound() {
        if (streaks.size() <= MAX_ENTRIES) {
            return;
        }
        while (streaks.size() > MAX_ENTRIES) {
            streaks.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().lastSeen()))
                    .ifPresent(entry -> streaks.remove(entry.getKey(), entry.getValue()));
        }
        log.warn("event=REAL_OWN_HOLDINGS_STREAKS_FULL entries={}", streaks.size());
    }
}
