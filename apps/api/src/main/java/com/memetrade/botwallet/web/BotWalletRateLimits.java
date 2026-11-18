package com.memetrade.botwallet.web;

import com.memetrade.shared.web.PersonRouteRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * NFR1.9 (security-design.md "Navegador → API", loop-back 1 of Build and Test): the limits per person of the routes of
 * the bot wallet, each with its own {@link PersonRouteRateLimiter} (window and limit as parameters since the U7). The
 * total withdrawal and every reading are never limited: the person can always take her money out. The idle keys are
 * dropped on every request (no scheduled job: at most one key per person and route of the pilot). In process memory,
 * like the other limiters of the single-instance beta.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class BotWalletRateLimits {

    /** The limited routes of the bot wallet, with the limit and the window of NFR1.9. */
    public enum Route {
        CHALLENGE("real-consent-challenge", 10, Duration.ofMinutes(1)),
        ACTIVATION("real-bot-wallet-activation", 3, Duration.ofDays(1)),
        PARTIAL_WITHDRAWAL("real-partial-withdrawal", 10, Duration.ofHours(1));

        private final String key;
        private final int limit;
        private final Duration window;

        Route(String key, int limit, Duration window) {
            this.key = key;
            this.limit = limit;
            this.window = window;
        }

        public int limit() {
            return limit;
        }

        public Duration window() {
            return window;
        }
    }

    private final Map<Route, PersonRouteRateLimiter> limiters = new EnumMap<>(Route.class);

    public BotWalletRateLimits(Clock clock) {
        for (Route route : Route.values()) {
            limiters.put(
                    route,
                    new PersonRouteRateLimiter(route.limit, route.window, clock, PersonRouteRateLimiter.MAX_KEYS));
        }
    }

    /** @return empty when accepted; otherwise the wait until the oldest request of the person leaves the window */
    public Optional<Duration> acquire(Route route, UUID userId) {
        PersonRouteRateLimiter limiter = limiters.get(route);
        limiter.evictIdle();
        return limiter.acquire(userId.toString(), route.key);
    }
}
