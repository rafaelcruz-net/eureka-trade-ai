package com.memetrade.riskcontrol.real.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * 261113-trading-real-bot U3 (U3-NFR8.3, security-requirements.md "Limites na configuração"): the five limits of the
 * REAL barrier, from {@code memetrade.real.risk.*}. The defaults are the values of the requirements; a value outside
 * its valid range refuses the start in every profile; outside the profiles {@code test} and {@code local} a value LOOSER
 * than the default (a higher percentage, more buys, a longer age or reservation) refuses the start too. Stricter
 * values are accepted. Messages name the key, never the value.
 *
 * @param orderMaxBps BR4.1, one buy at most this share of the current cap (10% = 1000)
 * @param dailyLossMaxBps BR4.3, the wallet pauses at this loss of the base of the day (20% = 2000)
 * @param dailyBuysMax BR4.2, approved buys of one bot per UTC day
 * @param signalMaxAge BR3.1 and BR2.4, the age of a market value a buy accepts
 * @param reservationTtl BR6.2, the time the U4 has to take over an approved buy
 */
public record RealRiskLimits(
        int orderMaxBps, int dailyLossMaxBps, int dailyBuysMax, Duration signalMaxAge, Duration reservationTtl) {

    public static final String PREFIX = "memetrade.real.risk.";

    public static final RealRiskLimits DEFAULTS =
            new RealRiskLimits(1000, 2000, 20, Duration.ofSeconds(60), Duration.ofMinutes(5));

    /** The widest values the profiles {@code test} and {@code local} accept, only for tests. */
    static final int TEST_MAX_BPS = 10_000;

    static final int TEST_MAX_BUYS = 10_000;
    static final Duration TEST_MAX_DURATION = Duration.ofHours(24);

    static final Duration MIN_SIGNAL_AGE = Duration.ofSeconds(5);
    static final Duration MIN_RESERVATION_TTL = Duration.ofMinutes(1);

    public RealRiskLimits {
        Objects.requireNonNull(signalMaxAge, PREFIX + "signal-max-age");
        Objects.requireNonNull(reservationTtl, PREFIX + "reservation-ttl");
    }

    /**
     * Checks every key at start-up.
     *
     * @param testProfile true only in the profiles {@code test} and {@code local}, where the upper bound may be
     *     extended for tests
     * @return this, when valid
     * @throws IllegalStateException naming the first key out of range or looser than its default
     */
    public RealRiskLimits checked(boolean testProfile) {
        requireBetween("order-max-bps", orderMaxBps, 1, DEFAULTS.orderMaxBps, testProfile ? TEST_MAX_BPS : null);
        requireBetween(
                "daily-loss-max-bps", dailyLossMaxBps, 1, DEFAULTS.dailyLossMaxBps, testProfile ? TEST_MAX_BPS : null);
        requireBetween("daily-buys-max", dailyBuysMax, 1, DEFAULTS.dailyBuysMax, testProfile ? TEST_MAX_BUYS : null);
        requireBetween(
                "signal-max-age",
                signalMaxAge,
                MIN_SIGNAL_AGE,
                DEFAULTS.signalMaxAge,
                testProfile ? TEST_MAX_DURATION : null);
        requireBetween(
                "reservation-ttl",
                reservationTtl,
                MIN_RESERVATION_TTL,
                DEFAULTS.reservationTtl,
                testProfile ? TEST_MAX_DURATION : null);
        return this;
    }

    private static void requireBetween(String key, int value, int min, int defaultMax, Integer testMax) {
        if (value < min) {
            throw new IllegalStateException(PREFIX + key + " is below its valid range");
        }
        if (value > defaultMax && (testMax == null || value > testMax)) {
            throw new IllegalStateException(PREFIX + key + " is looser than the default; only stricter values start");
        }
    }

    private static void requireBetween(
            String key, Duration value, Duration min, Duration defaultMax, Duration testMax) {
        if (value.compareTo(min) < 0) {
            throw new IllegalStateException(PREFIX + key + " is below its valid range");
        }
        if (value.compareTo(defaultMax) > 0 && (testMax == null || value.compareTo(testMax) > 0)) {
            throw new IllegalStateException(PREFIX + key + " is looser than the default; only stricter values start");
        }
    }
}
