package com.memetrade.realexits.fiscal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * BR7.7, U7-NFR3.8: the period of one export, two dates in the zone of the person, both included, at most one year
 * apart (365 days; 366 are refused), never inverted. Without dates, the 365 days ending today; with one date, the other
 * completes a year. The query reads the instants in UTC: from the start of {@code from} to the start of the day after
 * {@code to}, in the zone of the person (project.md: UTC in the database, the zone only for the person).
 */
public record FiscalPeriod(LocalDate from, LocalDate to, ZoneId zone) {

    /** BR7.7: at most one year per file. */
    static final long MAX_DAYS = 365;

    public FiscalPeriod {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(zone, "zone");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("O período está invertido: a data final vem antes da inicial.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new IllegalArgumentException("O período passa de 1 ano; exporte em partes de até 365 dias.");
        }
    }

    public static FiscalPeriod of(Optional<LocalDate> from, Optional<LocalDate> to, ZoneId zone, LocalDate today) {
        LocalDate end =
                to.orElseGet(() -> from.map(start -> start.plusDays(MAX_DAYS)).orElse(today));
        LocalDate start = from.orElseGet(() -> end.minusDays(MAX_DAYS));
        return new FiscalPeriod(start, end, zone);
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to);
    }

    /** The first instant of the period, in UTC. */
    public Instant start() {
        return from.atStartOfDay(zone).toInstant();
    }

    /** The first instant after the period (the day after {@code to}), in UTC. */
    public Instant end() {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** BR7, F4 step 3: the name of the download. */
    public String fileName() {
        return "historico-real-" + from + "-" + to + ".csv";
    }
}
